#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DEFAULT_MANIFEST="$ROOT/specs/scljet-capabilities.tsv"

validate_manifest() {
  local manifest=$1

  if ! awk -F '\t' '
    BEGIN {
      expectedHeader = "id\tstate\tcanonical_gate\tsource_path\tgate_path\towner"
      allowed["implemented"] = 1
      allowed["helper-only"] = 1
      allowed["subset"] = 1
      allowed["open"] = 1
      allowed["external"] = 1
      allowed["approval-gated"] = 1

      requiredIdsText = \
        "storage-balanced-insert storage-reclaiming-delete storage-freelist-reuse " \
        "storage-incremental-overflow storage-reserved-bytes storage-auto-vacuum " \
        "storage-indexed-multitable-dml storage-change-counter rollback-image-codec " \
        "rollback-reference-sharing sql-parser-curated-subset sql-affinity-semantics " \
        "provider-scljet wal-image-helpers wal-concurrency wal-no-shm-refusal " \
        "sql-official-families sql-advanced-schema extension-function-registry " \
        "extension-virtual-tables extension-disk-profile production-ci-matrix " \
        "production-benchmarks provider-sqlite-cutover ipk-insert-update-affinity " \
        "scalar-null-three-valued-logic scalar-numeric-blob-comparison schema-shared-model " \
        "constraint-explicit-unique-index constraint-column-table-primary-unique " \
        "constraint-not-null constraint-default constraint-check constraint-foreign-key " \
        "constraint-conflict-actions constraint-strict-generated typed-sql jdbc-portable " \
        "jdbc-jvm prepare-compiled-program planner-explain execution-register-vm " \
        "result-streaming transaction-savepoints portable-text-projection standalone-resolver " \
        "address-uniml f-bytecode-capacity"
      requiredCanonicalText = \
        "m3-write-balance m3-rollback m3-reference-share m4-parser-vm " \
        "m4-affinity-semantics m4-provider m5-wal-protocol m5-concurrency " \
        "m5-no-shm-refusal m6-families m6-advanced m7-functions m7-virtual-tables " \
        "m7-disk-profile m8-ci-matrix m8-benchmarks m8-provider-cutover"
      requiredIdCount = split(requiredIdsText, requiredIds, " ")
      requiredCanonicalCount = split(requiredCanonicalText, requiredCanonical, " ")
    }

    NR == 1 {
      if ($0 != expectedHeader) {
        printf "capability manifest header mismatch\nexpected=%s\ngot=%s\n", expectedHeader, $0 > "/dev/stderr"
        errors += 1
      }
      next
    }

    NF != 6 {
      printf "capability manifest line %d: expected 6 tab-separated fields, got %d\n", NR, NF > "/dev/stderr"
      errors += 1
      next
    }

    {
      id = $1
      state = $2
      canonical = $3
      gate = $5
      owner = $6

      if (seenId[id]++) {
        printf "capability manifest duplicate id: %s\n", id > "/dev/stderr"
        errors += 1
      }
      if (!allowed[state]) {
        printf "capability manifest %s: unknown state %s\n", id, state > "/dev/stderr"
        errors += 1
      }
      if (state == "implemented" && gate == "-") {
        printf "capability manifest %s: implemented requires a real gate path\n", id > "/dev/stderr"
        errors += 1
      }
      if (state == "external" && (owner == "" || owner == "scljet-production-completion")) {
        printf "capability manifest %s: external requires its foreign owner\n", id > "/dev/stderr"
        errors += 1
      }
      if (canonical != "-") {
        if (seenCanonical[canonical]++) {
          printf "capability manifest duplicate canonical gate: %s\n", canonical > "/dev/stderr"
          errors += 1
        }
      }
    }

    END {
      for (i = 1; i <= requiredIdCount; i++) {
        id = requiredIds[i]
        if (!(id in seenId)) {
          printf "capability manifest missing required capability: %s\n", id > "/dev/stderr"
          errors += 1
        }
      }
      for (i = 1; i <= requiredCanonicalCount; i++) {
        gate = requiredCanonical[i]
        if (!(gate in seenCanonical)) {
          printf "capability manifest missing canonical M3-M8 gate: %s\n", gate > "/dev/stderr"
          errors += 1
        }
      }
      if (errors) exit 1
      printf "capability manifest schema: ok (%d capabilities, %d canonical gates)\n", NR - 1, requiredCanonicalCount
    }
  ' "$manifest"; then
    return 1
  fi

  local line id state canonical sources gates owner old_ifs path
  line=1
  while IFS=$'\t' read -r id state canonical sources gates owner; do
    line=$((line + 1))
    old_ifs=$IFS
    IFS=','
    for path in $sources; do
      if [[ "$path" != "-" && ! -e "$ROOT/$path" ]]; then
        printf 'capability manifest %s: missing source path %s\n' "$id" "$path" >&2
        return 1
      fi
    done
    for path in $gates; do
      if [[ "$path" != "-" && ! -e "$ROOT/$path" ]]; then
        printf 'capability manifest %s: missing gate path %s\n' "$id" "$path" >&2
        return 1
      fi
    done
    IFS=$old_ifs
  done < <(tail -n +2 "$manifest")

  printf 'capability manifest paths: ok\n'
}

if [[ "${1:-}" == "--self-test" ]]; then
  validate_manifest "$DEFAULT_MANIFEST"
  TMP_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/scljet-capability-manifest.XXXXXX")
  trap 'rm -rf "$TMP_ROOT"' EXIT
  BROKEN_MANIFEST="$TMP_ROOT/missing-row.tsv"
  awk -F '\t' '$1 != "scalar-null-three-valued-logic"' "$DEFAULT_MANIFEST" > "$BROKEN_MANIFEST"
  if (validate_manifest "$BROKEN_MANIFEST" >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr"); then
    printf 'capability manifest red-path test failed: missing row was accepted\n' >&2
    exit 1
  fi
  if ! grep -q 'missing required capability: scalar-null-three-valued-logic' "$TMP_ROOT/stderr"; then
    printf 'capability manifest red-path diagnostic mismatch\nexpected=%s\ngot:\n' \
      'missing required capability: scalar-null-three-valued-logic' >&2
    sed -n '1,80p' "$TMP_ROOT/stderr" >&2
    exit 1
  fi
  printf 'capability manifest red path: ok\n'
else
  validate_manifest "${1:-$DEFAULT_MANIFEST}"
fi
