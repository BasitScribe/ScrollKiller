package com.scrollkiller

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every hand-written migration's `CREATE TABLE` must match Room's own generated schema, exactly.
 *
 * ## Why this is a test and not a code review
 * Room stores a hash of the expected schema and verifies it when the database opens. A migration
 * whose SQL differs from the generated schema by so much as a column ORDER throws
 * `IllegalStateException: Migration didn't properly handle...` — **at runtime, on the user's
 * device, on the upgrade launch**, which is the worst possible place to discover it. It cannot
 * happen on a fresh install, so it survives every ordinary test of the app.
 *
 * Both existing migrations carry a comment saying the statement "matches Room's generated schema
 * exactly". That was true and entirely unenforced. This asserts it.
 *
 * ## How it works
 * `exportSchema = true` writes `app/schemas/<db>/<version>.json` at build time. Each entity in it
 * carries a `createSql` with a `${TABLE_NAME}` placeholder. This parses the migration statements
 * out of `ScrollKillerDatabase.kt`, matches them to tables by name, and compares normalised SQL.
 *
 * Reading source and a build artifact rather than driving Room is the same tactic as
 * [BlockEscapeTest] (parses the shipping layout) and the backend's repo-layout tests: the failure
 * mode lives in a file, so the file is what gets asserted.
 */
class MigrationSqlTest {

    private fun repoFile(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.exists() }
            ?: error("not found from CWD ${File("").absolutePath}: ${candidates.joinToString()}")

    private fun schemaDir(): File = repoFile(
        "schemas/com.scrollkiller.data.db.ScrollKillerDatabase",
        "app/schemas/com.scrollkiller.data.db.ScrollKillerDatabase",
    )

    private fun databaseSource(): String = repoFile(
        "src/main/java/com/scrollkiller/data/db/ScrollKillerDatabase.kt",
        "app/src/main/java/com/scrollkiller/data/db/ScrollKillerDatabase.kt",
    ).readText()

    /** The newest exported schema — the one the current `version =` produces. */
    private fun latestSchema(): JSONObject {
        val newest = schemaDir().listFiles { f -> f.name.endsWith(".json") }
            ?.maxByOrNull { it.nameWithoutExtension.toInt() }
            ?: error("no exported schema JSON — is exportSchema still true?")
        return JSONObject(newest.readText()).getJSONObject("database")
    }

    /** Every string literal concatenated inside each `db.execSQL(...)` call, one entry per call. */
    private fun migrationStatements(): List<String> =
        Regex("""db\.execSQL\(\s*(.*?)\s*,\s*\)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(databaseSource())
            .map { call ->
                Regex(""""([^"]*)"""").findAll(call.groupValues[1])
                    .joinToString("") { it.groupValues[1] }
            }
            .toList()

    private fun normalise(sql: String) = sql.split(Regex("\\s+")).joinToString(" ").trim()

    @Test
    fun `every migrated table's CREATE matches Room's generated schema`() {
        val db = latestSchema()
        val entities = db.getJSONArray("entities")
        val statements = migrationStatements()
        var compared = 0

        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            // `createSql` uses a ${TABLE_NAME} placeholder that ALREADY sits inside backticks.
            val generated = normalise(entity.getString("createSql").replace("\${TABLE_NAME}", table))

            val hand = statements.map(::normalise).firstOrNull { it.contains("`$table`") }
                ?: continue // a table from v1 has no migration; nothing to compare

            assertEquals(
                "migration SQL for `$table` differs from Room's generated schema — this throws on " +
                    "the upgrade launch and cannot fail on a fresh install",
                generated,
                hand,
            )
            compared++
        }

        assertTrue(
            "no migration statements were compared — the parser probably stopped matching " +
                "ScrollKillerDatabase.kt, which would make this test silently vacuous",
            compared >= 3,
        )
    }

    @Test
    fun `the exported schema version matches the version declared in code`() {
        val declared = Regex("""version\s*=\s*(\d+)""").find(databaseSource())
            ?.groupValues?.get(1)?.toInt()
            ?: error("could not read `version =` from ScrollKillerDatabase.kt")

        assertEquals(
            "the newest exported schema does not match the declared version — someone bumped one " +
                "without rebuilding, so the migration is being checked against a stale schema",
            declared,
            latestSchema().getInt("version"),
        )
    }

    @Test
    fun `every version step from 1 to the current one has a migration`() {
        // A gap here means Room falls back to destructive migration or throws. Both lose user data
        // (D4 keeps aggregates forever precisely so it is never lost), and neither shows up until
        // an upgrade from the skipped version.
        val declared = latestSchema().getInt("version")
        val source = databaseSource()
        (1 until declared).forEach { from ->
            assertTrue(
                "no MIGRATION_${from}_${from + 1} — upgrading from v$from would lose data",
                source.contains("Migration($from, ${from + 1})"),
            )
            assertTrue(
                "MIGRATION_${from}_${from + 1} exists but is not registered in addMigrations()",
                source.contains("MIGRATION_${from}_${from + 1}"),
            )
        }
    }
}
