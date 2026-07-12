#include "sqlite3.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char *EXPECTED_VERSION = "3.53.3";
static const char *EXPECTED_SOURCE_ID =
    "2026-06-26 20:14:12 "
    "d4c0e51e4aeb96955b99185ab9cde75c339e2c29c3f3f12428d364a10d782c62";

static void fail(sqlite3 *db, const char *operation, int rc) {
  fprintf(stderr, "%s failed (%d): %s\n", operation, rc,
          db == NULL ? "no database" : sqlite3_errmsg(db));
  if (db != NULL) {
    sqlite3_close(db);
  }
  exit(1);
}

static void exec_sql(sqlite3 *db, const char *sql) {
  char *message = NULL;
  int rc = sqlite3_exec(db, sql, NULL, NULL, &message);
  if (rc != SQLITE_OK) {
    fprintf(stderr, "SQL failed: %s\n%s\n", sql,
            message == NULL ? sqlite3_errmsg(db) : message);
    sqlite3_free(message);
    sqlite3_close(db);
    exit(1);
  }
}

static void require_integrity(sqlite3 *db) {
  sqlite3_stmt *statement = NULL;
  int rc = sqlite3_prepare_v2(db, "PRAGMA integrity_check", -1, &statement, NULL);
  if (rc != SQLITE_OK) {
    fail(db, "prepare integrity_check", rc);
  }
  rc = sqlite3_step(statement);
  const unsigned char *result =
      rc == SQLITE_ROW ? sqlite3_column_text(statement, 0) : NULL;
  if (rc != SQLITE_ROW || result == NULL ||
      strcmp((const char *)result, "ok") != 0) {
    sqlite3_finalize(statement);
    fail(db, "integrity_check", rc);
  }
  sqlite3_finalize(statement);
}

int main(int argc, char **argv) {
  if (argc != 4) {
    fprintf(stderr, "usage: %s FILE PAGE_SIZE RESERVED_BYTES\n", argv[0]);
    return 2;
  }
  if (strcmp(sqlite3_libversion(), EXPECTED_VERSION) != 0 ||
      strcmp(sqlite3_sourceid(), EXPECTED_SOURCE_ID) != 0) {
    fprintf(stderr, "requires SQLite %s source id %s; got %s / %s\n",
            EXPECTED_VERSION, EXPECTED_SOURCE_ID, sqlite3_libversion(),
            sqlite3_sourceid());
    return 2;
  }

  char *end = NULL;
  long page_size = strtol(argv[2], &end, 10);
  if (*argv[2] == '\0' || *end != '\0' || page_size < 512 || page_size > 65536) {
    fprintf(stderr, "invalid page size: %s\n", argv[2]);
    return 2;
  }
  long reserved_long = strtol(argv[3], &end, 10);
  if (*argv[3] == '\0' || *end != '\0' || reserved_long < 0 || reserved_long > 255 ||
      page_size - reserved_long < 480) {
    fprintf(stderr, "invalid reserved-byte count: %s\n", argv[3]);
    return 2;
  }

  sqlite3 *db = NULL;
  int rc = sqlite3_open_v2(argv[1], &db,
                           SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, NULL);
  if (rc != SQLITE_OK) {
    fail(db, "open", rc);
  }

  char pragma[96];
  snprintf(pragma, sizeof(pragma), "PRAGMA page_size=%ld; VACUUM;", page_size);
  exec_sql(db, pragma);

  int reserved = (int)reserved_long;
  rc = sqlite3_file_control(db, "main", SQLITE_FCNTL_RESERVE_BYTES, &reserved);
  if (rc != SQLITE_OK) {
    fprintf(stderr, "SQLITE_FCNTL_RESERVE_BYTES requested %ld, failed (%d)\n",
            reserved_long, rc);
    sqlite3_close(db);
    return 1;
  }
  exec_sql(db, "VACUUM;");
  exec_sql(db,
           "CREATE TABLE t(a INTEGER, b TEXT, c BLOB);"
           "INSERT INTO t VALUES(7, 'reserved', x'0007ff');"
           "PRAGMA user_version=20260712;"
           "PRAGMA application_id=1396919370;");
  require_integrity(db);
  rc = sqlite3_close(db);
  if (rc != SQLITE_OK) {
    fail(db, "close", rc);
  }
  return 0;
}
