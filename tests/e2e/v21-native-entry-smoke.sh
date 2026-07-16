#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
FIXTURES="$ROOT/tests/fixtures/v21-native"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/v21-native-entry.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
mkdir -p "$sandbox/java-tmp"

[[ -x "$ROOT/bin/ssc" ]] || {
  echo 'v21-native-entry-smoke: run scripts/sbtc "installBin" first' >&2
  exit 2
}
[[ -x "$ROOT/bin/ssc-standard" ]] || {
  echo 'v21-native-entry-smoke: staged standard launcher missing' >&2
  exit 2
}
[[ -f "$ROOT/bin/lib/native-front/tower/bin/ssc1-run.ssc0" ]] || {
  echo 'v21-native-entry-smoke: staged native frontend missing' >&2
  exit 2
}
[[ -f "$ROOT/bin/lib/native-front/runtime/std/os.ssc" ]] || {
  echo 'v21-native-entry-smoke: staged std modules missing' >&2
  exit 2
}

clean_path='/usr/bin:/bin'
if PATH="$clean_path" command -v scala-cli >/dev/null 2>&1; then
  echo 'v21-native-entry-smoke: sanitized PATH unexpectedly contains scala-cli' >&2
  exit 1
fi

run_native() {
  PATH="$clean_path" JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$sandbox/java-tmp" \
    SSC_STORAGE_PATH="$sandbox/storage.json" SSC_NO_CDS=1 \
    "$ROOT/bin/ssc" run --native "$@"
}

run_standard() {
  PATH="$clean_path" JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$sandbox/java-tmp" \
    SSC_STORAGE_PATH="$sandbox/storage.json" \
    "$ROOT/bin/ssc-standard" run "$@"
}

[[ $(run_native "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]
[[ $(run_native "$FIXTURES/relative-main.ssc") == '42' ]]
[[ $(run_native "$FIXTURES/multi-first.ssc" "$FIXTURES/multi-second.ssc") == $'first\nsecond' ]]
[[ $(run_native "$FIXTURES/argv.ssc" -- one two) == $'one\ntwo' ]]
[[ $(run_native "$FIXTURES/std-import.ssc") == 'std-import-ok' ]]
[[ $(run_native "$FIXTURES/std-crypto.ssc") == '2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824' ]]
fs_os_expected=$'one-two\nsample.txt\ntrue\nfallback\ntrue\nfalse'
[[ $(run_native "$FIXTURES/fs-os-provider.ssc") == "$fs_os_expected" ]]
json_expected=$'Ada\n2\ntrue\n1000.01\ntrue\n{"payload":[1,2]}\n[1,2,3]\n{"name":"A\\"B","on":true}'
[[ $(run_native "$FIXTURES/json-provider.ssc") == "$json_expected" ]]
http_response_expected=$'201\ntext/plain; charset=utf-8\nhello\n{"n":2,"ok":true}\npublic, max-age=60\nv1\nno-store'
[[ $(run_native "$FIXTURES/http-response-provider.ssc") == "$http_response_expected" ]]
sql_expected=$'1\n7\nAda\ntrue'
[[ $(run_native "$FIXTURES/sql-provider.ssc") == "$sql_expected" ]]
sql_fence_expected=$'0\n1\n1\nAda\n$\n1'
[[ $(run_standard "$FIXTURES/sql-fence-provider.ssc") == "$sql_fence_expected" ]]
[[ $(run_standard --bytecode "$FIXTURES/sql-fence-provider.ssc") == "$sql_fence_expected" ]]
sql_quickstart_expected=$'active users: List(Map(ID -> 1, NAME -> Alice, EMAIL -> alice@example.com), Map(ID -> 2, NAME -> Bob, EMAIL -> bob@example.com))\nusers with id >= 1: List(Map(TOTAL -> 3))'
[[ $(run_standard "$ROOT/examples/sql-h2-quickstart.ssc") == "$sql_quickstart_expected" ]]
[[ $(run_standard --bytecode "$ROOT/examples/sql-h2-quickstart.ssc") == "$sql_quickstart_expected" ]]
typed_sql_expected='1/1:Buy oat milk:true'
[[ $(run_standard "$ROOT/examples/typed-sql-crud.ssc") == "$typed_sql_expected" ]]
[[ $(run_standard --bytecode "$ROOT/examples/typed-sql-crud.ssc") == "$typed_sql_expected" ]]
for sql_negative in \
    'sql-fence-malformed-bind.ssc:__malformed_sql_bind' \
    'sql-fence-client-only.ssc:__unsupported_client_sql_fence'; do
  fixture=${sql_negative%%:*}
  sentinel=${sql_negative#*:}
  for backend in vm asm; do
    backend_args=()
    [[ $backend == asm ]] && backend_args=(--bytecode)
    if run_standard "${backend_args[@]}" "$FIXTURES/$fixture" \
        >"$sandbox/sql-negative.out" 2>"$sandbox/sql-negative.err"; then
      echo "v21-native-entry-smoke: $fixture unexpectedly passed on $backend" >&2
      exit 1
    fi
    [[ ! -s "$sandbox/sql-negative.out" ]]
    grep -Fx "ssc: unbound global: $sentinel" "$sandbox/sql-negative.err" >/dev/null
  done
done
for typed_sql_negative in \
    'typed-sql-missing-column.ssc:Db.query[Todo] missing column: done' \
    'typed-sql-invalid-identifier.ssc:Db.insert invalid SQL identifier: todos;drop'; do
  fixture=${typed_sql_negative%%:*}
  diagnostic=${typed_sql_negative#*:}
  for backend in vm asm; do
    backend_args=()
    [[ $backend == asm ]] && backend_args=(--bytecode)
    if run_standard "${backend_args[@]}" "$FIXTURES/$fixture" \
        >"$sandbox/typed-sql-negative.out" 2>"$sandbox/typed-sql-negative.err"; then
      echo "v21-native-entry-smoke: $fixture unexpectedly passed on $backend" >&2
      exit 1
    fi
    [[ ! -s "$sandbox/typed-sql-negative.out" ]]
    grep -Fx "ssc: $diagnostic" "$sandbox/typed-sql-negative.err" >/dev/null
  done
done
ui_expected=$'<!doctype html>\n<main class="card" id="app"><h1>Hi &lt;native&gt;</h1>Ada<span>shown</span></main>'
ui_vm="$sandbox/ui-vm"
ui_asm="$sandbox/ui-asm"
[[ $(run_native "$FIXTURES/ui-provider.ssc" -- "$ui_vm") == "$ui_expected" ]]
[[ $(<"$ui_vm/index.html") == "$ui_expected" ]]
state_expected=$'17\n20\n2\n101\n101\n2'
[[ $(run_native "$FIXTURES/state-effect-provider.ssc") == "$state_expected" ]]
dataset_expected=$'6,7,4,5,4,5\n1,2\n3,1,2,2,4\n3,2\n(3, a),(1, b)\n(3, 0),(1, 1),(2, 2),(2, 3)\n(1, 3-1),(0, 2-2)\n(1, 4),(0, 4)\n1,2,2,3\ncount=4 sum=8 avg=2\nmin=1 max=3\ntop=3,2 ordered=1,2\nreduce=8 fold=18 first=Some(3)\ntwos=2\n(List(2, 2), List(3, 1))\n[3,1,2,2]\nMap(3 -> c, 1 -> a, 2 -> z)\n3,1,2\nparallel=333383335000 count=10000'
[[ $(run_native "$FIXTURES/dataset-provider.ssc") == "$dataset_expected" ]]
generator_expected=$'List(1, 2, 3)\nSome(10)\nSome(20)\nNone\na\nb\nc\nList(2, 4, 6)\nList(0, 1, 1, 2, 3, 5, 8, 13)\nList(30, 40)\nList(1, 10, 2, 20, 3, 30)\nList((1, a), (2, b))\nList((hello, 0), (world, 1), (foo, 2))'
[[ $(run_native "$ROOT/examples/generators.ssc") == "$generator_expected" ]]
generator_provider_expected=$'Some(1)\nSome(2)\nNone\nList(0, 1, 2, 3, 4)\nList(1, 10, 2, 20)\nList((x, 0), (y, 1))'
[[ $(run_native "$FIXTURES/generator-provider.ssc") == "$generator_provider_expected" ]]
async_expected=$'6\nList(1, 4, 9, 16)\nafter delay\nList(20, 40, 60)\n56'
[[ $(run_native "$ROOT/examples/async-demo.ssc") == "$async_expected" ]]
async_provider_expected=$'3\nList(30, 20, 10)\n8\nafter-zero'
[[ $(run_native "$FIXTURES/async-provider.ssc") == "$async_provider_expected" ]]
actors_expected=$'pong: one\npong: two\npong: three\nafter timeout: None\nbefore timeout: Some(got delivered)\ndone'
[[ $(run_native "$ROOT/examples/actors-pingpong.ssc") == "$actors_expected" ]]
typed_actors_expected=$'true\ntrue\nlocal ref\nspawnRemote: pong'
[[ $(run_native "$ROOT/examples/actors-typed-remote-spawn.ssc") == "$typed_actors_expected" ]]
actors_provider_expected=$'worker: one\nSome(root: reply)'
[[ $(run_native "$FIXTURES/actors-provider.ssc") == "$actors_provider_expected" ]]
distributed_join_expected=$'o1 | c1 | Ada | 10\no2 | c2 | Bob | 20\no3 | c1 | Ada | 30'
[[ $(run_native "$ROOT/examples/distributed-join.ssc" -- \
  "$FIXTURES/distributed-orders.csv" "$FIXTURES/distributed-customers.csv") == "$distributed_join_expected" ]]
distributed_log_expected=$'payments: 2 errors\nsearch: 1 errors'
[[ $(run_native "$ROOT/examples/distributed-log-aggregation.ssc" -- \
  "$FIXTURES/distributed-app.log") == "$distributed_log_expected" ]]
[[ $(run_native "$ROOT/examples/graph-storage-interpreter.ssc") == 'imports:b.ssc' ]]
parameterless_expected=$'5\n10\n1\n2\n2\n5\n3\n6\n5'
[[ $(run_native "$FIXTURES/parameterless-def-main.ssc") == "$parameterless_expected" ]]
dsl_mini_language_expected=$'=== success: 2 * x + y ===\nresult: 23\n=== name-resolve error: x + z ===\nPassError(name-resolve, undefined variable: z, <unknown>, 0, 0)\n=== type-check error: x / 0 ===\nPassError(type-check, division by zero, <unknown>, 0, 0)\n=== parse error: 1 @ 2 ===\nPassError(parse, cannot parse atom: 1 @ 2, <unknown>, 0, 0)\n=== pipeline report: 2 * x + y ===\n  [parse] ok\n  [name-resolve] ok\n  [type-check] ok\n  [evaluate] ok'
[[ $(run_native "$ROOT/examples/dsl-mini-language.ssc") == "$dsl_mini_language_expected" ]]
custom_derives_expected=$'Person\nname|age\nString|Int\nname,age'
[[ $(run_native "$ROOT/examples/custom-derives-mirror.ssc") == "$custom_derives_expected" ]]
product_derives_expected=$'Person\nname|age\nString|Int\nPerson\nname,age|name,age\n1'
[[ $(run_native "$FIXTURES/product-derives.ssc") == "$product_derives_expected" ]]
direct_syntax_expected=$'Some(Profile(User(Alice, 30), functional programmer))\nNone\nNone\nSome(50)\nNone\nS-red, S-blue, M-red, M-blue, L-red, L-blue\nSome(order confirmed)\nNone\nSome(30)\nNone\nSome(60)'
[[ $(run_native "$ROOT/examples/direct-syntax-demo.ssc") == "$direct_syntax_expected" ]]
direct_option_list_expected=$'None\n0\n1a,1b,2a,2b\nSome(7)\nSome(6)'
[[ $(run_native "$FIXTURES/direct-option-list.ssc") == "$direct_option_list_expected" ]]
named_copy_expected=$'Alice|31|Boston\nAlicia|30|Paris\nBob|40|Boston\n1|9\nRCN\nAlina|30|Kyiv'
[[ $(run_native "$FIXTURES/named-copy.ssc") == "$named_copy_expected" ]]
[[ $(run_standard "$FIXTURES/named-copy.ssc") == "$named_copy_expected" ]]
lenses_expected=$'older   : Alice, 31\nrenamed : Bob, 40, Boston\n30\n99\n40\nBoston\nParis\nMain St\nMain St\nBroadway\nSome(Circle(5))\nNone\nCircle(10)\nRect(3, 4)\nSome(Boston)\nNone\nNone\nSome(Profile(Some(Address(Main St, Paris))))\nNone\nSome(Boston)\nList(Alice, Bob, Carol)\nList(31, 26, 36)\nList(TeamMember(anon, 30), TeamMember(anon, 25), TeamMember(anon, 35))'
[[ $(run_standard "$ROOT/examples/lenses.ssc") == "$lenses_expected" ]]
[[ $(run_standard --bytecode "$ROOT/examples/lenses.ssc") == "$lenses_expected" ]]
[[ $(run_native "$FIXTURES/zero-arg-println.ssc") == $'before\n\nafter' ]]
signals_expected=$'0\n5\n10\nc=5 d=10\nc=7 d=14\nc=11 d=22\nn=3 sq=9 cube=27\nn=4 sq=16 cube=64'
[[ $(run_native "$ROOT/examples/signals-demo.ssc") == "$signals_expected" ]]
yaml_expected=$'Type:   YObj\nHost:   localhost\nPort:   8080\nDebug:  true\nTags:   web, api\n\nRound-trip:\ndebug: true\nhost: localhost\nport: 8080\n\nFrom fenced block:\nApp: MyApp'
[[ $(run_native "$ROOT/examples/yaml-parse.ssc") == "$yaml_expected" ]]
[[ $(run_native "$FIXTURES/yaml-sections.ssc") == $'first\nsecond' ]]
[[ $(run_native "$FIXTURES/yaml-section-import-main.ssc") == 'imported' ]]
[[ $(run_native "$FIXTURES/prefix-postfix.ssc") == $'true\n-1\n-2' ]]
interpolation_expected=$'Squares: 1, 4, 9, 16, 25\nWrapped: 1-4-9-16-25!'
[[ $(run_native "$FIXTURES/interpolation-expression.ssc") == "$interpolation_expected" ]]
[[ $(run_native "$FIXTURES/multiline-function-param.ssc") == '[typed]' ]]
[[ $(run_native "$FIXTURES/nested-tuple-pattern.ssc") == $'left\nleft+right' ]]
[[ $(run_native "$FIXTURES/numeric-separator.ssc") == '10000' ]]
triple_string_expected=$'first line\n"quoted" line\nlast line'
[[ $(run_native "$FIXTURES/triple-quoted-string.ssc") == "$triple_string_expected" ]]
enum_boundary_expected=$'Red\nBox(7)'
[[ $(run_native "$FIXTURES/enum-case-class-boundary.ssc") == "$enum_boundary_expected" ]]
[[ $(run_native "$FIXTURES/multiline-tuple-lambda.ssc") == '11' ]]
guard_expected=$'negative\nzero\nsmall\nlarge'
[[ $(run_native "$FIXTURES/binder-match-guard.ssc") == "$guard_expected" ]]
constructor_guard_expected=$'enough\nlow\nmissing'
[[ $(run_native "$FIXTURES/constructor-match-guard.ssc") == "$constructor_guard_expected" ]]
[[ $(run_native "$FIXTURES/extension-declaration.ssc") == 'extension-header-ok' ]]
[[ $(run_native "$FIXTURES/list-append.ssc") == '1,2,3,4' ]]
[[ $(run_native "$FIXTURES/symbolic-extension-operators.ssc") == 'symbolic-operators-ok' ]]
pattern_alternative_expected=$'hit\nhit\nmiss'
[[ $(run_native "$FIXTURES/constructor-pattern-alternative.ssc") == "$pattern_alternative_expected" ]]
assignment_expression_expected=$'6\ntrue\n7'
[[ $(run_native "$FIXTURES/assignment-expression.ssc") == "$assignment_expression_expected" ]]
final_mini_language_expected=$'condition-ok\nstage\n7\ntrue'
[[ $(run_native "$FIXTURES/final-mini-language-shapes.ssc") == "$final_mini_language_expected" ]]
[[ $(run_native "$FIXTURES/multiple-link-imports.ssc") == '42' ]]
[[ $(run_native "$FIXTURES/multiline-link-import.ssc") == '82' ]]
bind_pattern_expected=$'BoundEnvelope(Some(BoundBox(7)), ok)|7\n1\nfallback:none\n2'
[[ $(run_native "$FIXTURES/bind-pattern.ssc") == "$bind_pattern_expected" ]]
sql_recovery_expected=$'--- input: SELECT * FROM users\nparse-succeeded: false\nerror-count: 1\n  error @ 0: unknown parser node\n--- input: SELECT name BORKED users\nparse-succeeded: false\nerror-count: 1\n  error @ 0: unknown parser node\n--- input: BOGUS QUERY HERE\nparse-succeeded: false\nerror-count: 1\n  error @ 0: unknown parser node\n--- input: SELECT id FROM orders WHERE status = \'open\'\nparse-succeeded: false\nerror-count: 1\n  error @ 0: unknown parser node'
[[ $(run_native "$ROOT/examples/dsl-sql-recovery.ssc") == "$sql_recovery_expected" ]]
imported_tuple_expected='Ada:90|Lin=88|Some(Cy/77)'
[[ $(run_native "$FIXTURES/imported-tuple-collection.ssc") == "$imported_tuple_expected" ]]
exact_decimal_expected=$'12.35\n10.00\n13.00\n3.60\ntrue\n10\ndue: 12.35'
[[ $(run_native "$FIXTURES/exact-decimal.ssc") == "$exact_decimal_expected" ]]
[[ $(run_native "$ROOT/examples/multi-link-imports.ssc") == 'minor units: 1234' ]]
default_arguments_expected=$'World\nAda\nAda!\n21\n15\n3\n1\n10\n20\n1\n7'
[[ $(run_native "$FIXTURES/default-arguments.ssc") == "$default_arguments_expected" ]]
collection_companions_expected=$'0,1,4,9\n1-2-3\n4-5-6\nx,x,x\n2,3,4,5\n10\n12\n7'
[[ $(run_native "$FIXTURES/collection-companions.ssc") == "$collection_companions_expected" ]]
extension_receivers_expected=$'HELLO!\nhahaha\n25\n20\n5'
[[ $(run_native "$FIXTURES/extension-receivers.ssc") == "$extension_receivers_expected" ]]
dynamic_length_expected=$'3\n8\n6\n4'
[[ $(run_native "$FIXTURES/dynamic-length.ssc") == "$dynamic_length_expected" ]]
dynamic_to_int_expected=$'42\n8\n1\n9'
[[ $(run_native "$FIXTURES/dynamic-string-toint.ssc") == "$dynamic_to_int_expected" ]]
top_level_while_expected=$'0\n10\n4'
[[ $(run_native "$FIXTURES/top-level-while.ssc") == "$top_level_while_expected" ]]
layout_given_expected=$'int\nint:7\nbool:yes\nbool:no\nafter-givens'
[[ $(run_native "$FIXTURES/layout-given-objects.ssc") == "$layout_given_expected" ]]
layout_object_expected=$'40\n41\n81\n40\n41\n81'
[[ $(run_native "$FIXTURES/layout-object-body.ssc") == "$layout_object_expected" ]]
extension_layout_expected=$'20\n22\nrx\nfallback'
[[ $(run_native "$FIXTURES/extension-layout-boundary.ssc") == "$extension_layout_expected" ]]
extension_receiver_scope_expected=$'2\n2\n5\n3\n3\n7\n9'
[[ $(run_native "$FIXTURES/extension-receiver-scope.ssc") == "$extension_receiver_scope_expected" ]]
imported_pmapped_expected=$'22\n0\n0'
[[ $(run_native "$FIXTURES/imported-pmapped.ssc") == "$imported_pmapped_expected" ]]
curried_fold_expected=$'1, 3, 6, 10, 15\n10'
[[ $(run_native "$FIXTURES/curried-fold-left.ssc") == "$curried_fold_expected" ]]
case_object_expected=$'Empty\nempty\ntrue'
[[ $(run_native "$FIXTURES/case-object-import.ssc") == "$case_object_expected" ]]
symbolic_extension_precedence_expected=$'a|b\na|b|c\n7'
[[ $(run_native "$FIXTURES/symbolic-extension-precedence.ssc") == "$symbolic_extension_precedence_expected" ]]
typed_pattern_boundary_expected=$'3\ndeep\nshallow\n-1'
[[ $(run_native "$FIXTURES/typed-pattern-boundary.ssc") == "$typed_pattern_boundary_expected" ]]
tuple_field_pattern_expected=$'customer:c1:Ada\norder:o1:c1:10\nother\nother\nentry:ok\nother'
[[ $(run_native "$FIXTURES/tuple-field-pattern.ssc") == "$tuple_field_pattern_expected" ]]
native_math_expected=$'3141593\n2718282\n42\n25\n90\n1024'
[[ $(run_native "$FIXTURES/native-math-object.ssc") == "$native_math_expected" ]]
exact_summon_expected=$'show:7\ntrue\nnested'
[[ $(run_native "$FIXTURES/exact-summon.ssc") == "$exact_summon_expected" ]]
typeclass_dictionary_expected=$'int\n0\n7\nstring\n[]\nleft-right\nint|string\n17\n21'
[[ $(run_native "$FIXTURES/typeclass-dictionary.ssc") == "$typeclass_dictionary_expected" ]]
typeclass_example_expected=$'Int    : Int(42)\nBool   : yes\nString : \'hello\'\nsummon : Int(99)\n1 == 1  : true\n1 == 2  : false\nhi == hi: true\nhi == ho: false\n3 < 7   : true\n5 > 2   : true\nmin(3,7): 3\nmax(3,7): 7\nsorted  : 1, 2, 3, 5, 8, 9\nsum    : 15\nconcat : hello, world!\nrepeat : abababab\ndoubled: 2, 4, 6, 8, 10\nsquared: 1, 4, 9, 16, 25'
[[ $(run_native "$ROOT/examples/typeclass.ssc") == "$typeclass_example_expected" ]]
nested_pattern_expected=$'some:7:outer\nnone:2:outer\ndeep:9\ninner-none\nfallback:kept'
[[ $(run_native "$FIXTURES/nested-pattern-fallback.ssc") == "$nested_pattern_expected" ]]
list_mkstring_expected=$'[]\none\none, two, three\n1|2|3'
[[ $(run_native "$FIXTURES/list-mkstring-capture.ssc") == "$list_mkstring_expected" ]]
crypto_encrypt_expected=$'AES-256-GCM round-trip ok: true\nRSA-OAEP wrapped session key (base64, 344 chars) produced\nAES-256-CBC round-trip ok: true'
[[ $(run_native "$ROOT/examples/crypto-encrypt-demo.ssc") == "$crypto_encrypt_expected" ]]
crypto_verify_expected=$'signature valid: true\ntampered valid: false\nmalformed valid: false\nsignature matches vector: true\nround-trip valid: true'
[[ $(run_native "$ROOT/examples/crypto-verify-demo.ssc") == "$crypto_verify_expected" ]]
totp_shamir_expected=$'TOTP code: 14050471\ncode valid: true\nwrong code valid: false\nsplit into 3 shares\nrecovered matches: true\none share recovers secret: false'
[[ $(run_native "$ROOT/examples/totp-shamir-demo.ssc") == "$totp_shamir_expected" ]]
storage_expected=$'Some(alice)\nNone\ntrue\nList(user, role)\n1\n2\n1\n3\nList(hits:alice, hits:bob)\nSome(hello world)'
[[ $(run_native "$ROOT/examples/storage-demo.ssc") == "$storage_expected" ]]
ui_fetch_json_expected=$'body:{"name":"Acme \\"HQ\\"","n":5}\nfetch-json:ok'
[[ $(run_native "$ROOT/examples/ui-fetch-json.ssc") == "$ui_fetch_json_expected" ]]
index_expected=$'ScalaScript 0.1 is running!\nSquares: 1, 4, 9, 16, 25'
[[ $(run_native "$ROOT/examples/index.ssc") == "$index_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]
[[ $(run_native --bytecode "$FIXTURES/prefix-postfix.ssc") == $'true\n-1\n-2' ]]
[[ $(run_native --bytecode "$FIXTURES/interpolation-expression.ssc") == "$interpolation_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/multiline-function-param.ssc") == '[typed]' ]]
[[ $(run_native --bytecode "$FIXTURES/nested-tuple-pattern.ssc") == $'left\nleft+right' ]]
[[ $(run_native --bytecode "$FIXTURES/numeric-separator.ssc") == '10000' ]]
[[ $(run_native --bytecode "$FIXTURES/triple-quoted-string.ssc") == "$triple_string_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/enum-case-class-boundary.ssc") == "$enum_boundary_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/multiline-tuple-lambda.ssc") == '11' ]]
[[ $(run_native --bytecode "$FIXTURES/binder-match-guard.ssc") == "$guard_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/constructor-match-guard.ssc") == "$constructor_guard_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/extension-declaration.ssc") == 'extension-header-ok' ]]
[[ $(run_native --bytecode "$FIXTURES/list-append.ssc") == '1,2,3,4' ]]
[[ $(run_native --bytecode "$FIXTURES/symbolic-extension-operators.ssc") == 'symbolic-operators-ok' ]]
[[ $(run_native --bytecode "$FIXTURES/constructor-pattern-alternative.ssc") == "$pattern_alternative_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/assignment-expression.ssc") == "$assignment_expression_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/final-mini-language-shapes.ssc") == "$final_mini_language_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/multiple-link-imports.ssc") == '42' ]]
[[ $(run_native --bytecode "$FIXTURES/multiline-link-import.ssc") == '82' ]]
[[ $(run_native --bytecode "$FIXTURES/bind-pattern.ssc") == "$bind_pattern_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/dataset-provider.ssc") == "$dataset_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/generators.ssc") == "$generator_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/generator-provider.ssc") == "$generator_provider_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/async-demo.ssc") == "$async_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/async-provider.ssc") == "$async_provider_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/actors-pingpong.ssc") == "$actors_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/actors-typed-remote-spawn.ssc") == "$typed_actors_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/actors-provider.ssc") == "$actors_provider_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/distributed-join.ssc" -- \
  "$FIXTURES/distributed-orders.csv" "$FIXTURES/distributed-customers.csv") == "$distributed_join_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/distributed-log-aggregation.ssc" -- \
  "$FIXTURES/distributed-app.log") == "$distributed_log_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/graph-storage-interpreter.ssc") == 'imports:b.ssc' ]]
[[ $(run_native --bytecode "$FIXTURES/parameterless-def-main.ssc") == "$parameterless_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/dsl-mini-language.ssc") == "$dsl_mini_language_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/custom-derives-mirror.ssc") == "$custom_derives_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/product-derives.ssc") == "$product_derives_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/direct-syntax-demo.ssc") == "$direct_syntax_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/direct-option-list.ssc") == "$direct_option_list_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/named-copy.ssc") == "$named_copy_expected" ]]
[[ $(run_standard --bytecode "$FIXTURES/named-copy.ssc") == "$named_copy_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/dsl-sql-recovery.ssc") == "$sql_recovery_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/imported-tuple-collection.ssc") == "$imported_tuple_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/exact-decimal.ssc") == "$exact_decimal_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/multi-link-imports.ssc") == 'minor units: 1234' ]]
[[ $(run_native --bytecode "$FIXTURES/default-arguments.ssc") == "$default_arguments_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/collection-companions.ssc") == "$collection_companions_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/extension-receivers.ssc") == "$extension_receivers_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/dynamic-length.ssc") == "$dynamic_length_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/dynamic-string-toint.ssc") == "$dynamic_to_int_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/top-level-while.ssc") == "$top_level_while_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/layout-given-objects.ssc") == "$layout_given_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/layout-object-body.ssc") == "$layout_object_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/extension-layout-boundary.ssc") == "$extension_layout_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/extension-receiver-scope.ssc") == "$extension_receiver_scope_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/imported-pmapped.ssc") == "$imported_pmapped_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/curried-fold-left.ssc") == "$curried_fold_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/case-object-import.ssc") == "$case_object_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/symbolic-extension-precedence.ssc") == "$symbolic_extension_precedence_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/typed-pattern-boundary.ssc") == "$typed_pattern_boundary_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/tuple-field-pattern.ssc") == "$tuple_field_pattern_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/native-math-object.ssc") == "$native_math_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/exact-summon.ssc") == "$exact_summon_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/typeclass-dictionary.ssc") == "$typeclass_dictionary_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/typeclass.ssc") == "$typeclass_example_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/nested-pattern-fallback.ssc") == "$nested_pattern_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/list-mkstring-capture.ssc") == "$list_mkstring_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/crypto-encrypt-demo.ssc") == "$crypto_encrypt_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/crypto-verify-demo.ssc") == "$crypto_verify_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/totp-shamir-demo.ssc") == "$totp_shamir_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/storage-demo.ssc") == "$storage_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/ui-fetch-json.ssc") == "$ui_fetch_json_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/index.ssc") == "$index_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/fs-os-provider.ssc") == "$fs_os_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/json-provider.ssc") == "$json_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/http-response-provider.ssc") == "$http_response_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/sql-provider.ssc") == "$sql_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/ui-provider.ssc" -- "$ui_asm") == "$ui_expected" ]]
[[ $(<"$ui_asm/index.html") == "$ui_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/state-effect-provider.ssc") == "$state_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/zero-arg-println.ssc") == $'before\n\nafter' ]]
[[ $(run_native --bytecode "$ROOT/examples/signals-demo.ssc") == "$signals_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/yaml-parse.ssc") == "$yaml_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/yaml-sections.ssc") == $'first\nsecond' ]]
[[ $(run_native --bytecode "$FIXTURES/yaml-section-import-main.ssc") == 'imported' ]]

assert_frontmatter_failure() {
  local name=$1
  shift
  set +e
  run_native "$@" >"$sandbox/$name.out" 2>"$sandbox/$name.err"
  local rc=$?
  set -e
  [[ $rc -ne 0 ]]
  [[ ! -s "$sandbox/$name.out" ]]
}

assert_frontmatter_failure yaml-duplicate "$FIXTURES/yaml-duplicate-frontmatter.ssc"
grep -E 'yaml-duplicate-frontmatter[.]ssc:4:5: duplicate mapping key' \
  "$sandbox/yaml-duplicate.err" >/dev/null
assert_frontmatter_failure yaml-anchor "$FIXTURES/yaml-anchor-frontmatter.ssc"
grep -F 'anchors, aliases, and tags are not supported' "$sandbox/yaml-anchor.err" >/dev/null
assert_frontmatter_failure yaml-missing-url "$FIXTURES/yaml-missing-url.ssc"
grep -F "native database 'default'" "$sandbox/yaml-missing-url.err" >/dev/null
grep -F 'requires a non-empty url' "$sandbox/yaml-missing-url.err" >/dev/null
assert_frontmatter_failure yaml-conflict \
  "$FIXTURES/yaml-conflict-a.ssc" "$FIXTURES/yaml-conflict-b.ssc"
grep -F "conflicting native database 'default'" "$sandbox/yaml-conflict.err" >/dev/null

for mode in vm asm; do
  mode_args=()
  [[ $mode == asm ]] && mode_args=(--bytecode)
  set +e
  run_native "${mode_args[@]}" "$ROOT/examples/graph-rdf4j-http-storage.ssc" \
    >"$sandbox/graph-rdf4j.$mode.out" 2>"$sandbox/graph-rdf4j.$mode.err"
  graph_rdf_rc=$?
  set -e
  [[ $graph_rdf_rc -ne 0 ]]
  [[ $(cat "$sandbox/graph-rdf4j.$mode.out") == 'Stored two books.' ]]
  grep -F 'unhandled runtime effect: Sparql.select' \
    "$sandbox/graph-rdf4j.$mode.err" >/dev/null
done

for mode in vm asm; do
  mode_args=()
  [[ $mode == asm ]] && mode_args=(--bytecode)
  set +e
  run_standard "${mode_args[@]}" "$FIXTURES/optics-unsupported.ssc" \
    >"$sandbox/optics-unsupported.$mode.out" \
    2>"$sandbox/optics-unsupported.$mode.err"
  optics_unsupported_rc=$?
  set -e
  [[ $optics_unsupported_rc -ne 0 ]]
  [[ ! -s "$sandbox/optics-unsupported.$mode.out" ]]
  grep -F 'unbound global: __unsupported_focus_path' \
    "$sandbox/optics-unsupported.$mode.err" >/dev/null
done

for mode in vm asm; do
  mode_args=()
  [[ $mode == asm ]] && mode_args=(--bytecode)
  set +e
  run_native "${mode_args[@]}" "$FIXTURES/direct-unsupported.ssc" \
    >"$sandbox/direct-unsupported.$mode.out" \
    2>"$sandbox/direct-unsupported.$mode.err"
  direct_unsupported_rc=$?
  set -e
  [[ $direct_unsupported_rc -ne 0 ]]
  [[ ! -s "$sandbox/direct-unsupported.$mode.out" ]]
  grep -F 'unbound global: __unsupported_direct_Either' \
    "$sandbox/direct-unsupported.$mode.err" >/dev/null
done

for mode in vm asm; do
  mode_args=()
  [[ $mode == asm ]] && mode_args=(--bytecode)
  for name in default-arguments-required default-arguments-over-arity; do
    set +e
    run_native "${mode_args[@]}" "$FIXTURES/$name.ssc" \
      >"$sandbox/$name.$mode.out" 2>"$sandbox/$name.$mode.err"
    default_failure_rc=$?
    set -e
    [[ $default_failure_rc -ne 0 ]]
    [[ ! -s "$sandbox/$name.$mode.out" ]]
    grep -E '(ssc:|run --native:) (arity:|TYPEERR:)' \
      "$sandbox/$name.$mode.err" >/dev/null
    if grep -E 'parser sentinel _err|match: no arm|StackOverflowError' \
      "$sandbox/$name.$mode.err" >/dev/null; then
      echo "$name $mode did not preserve an honest fixed-arity failure" >&2
      exit 1
    fi
  done
done

for mode in vm asm; do
  mode_args=()
  [[ $mode == asm ]] && mode_args=(--bytecode)
  set +e
  run_native "${mode_args[@]}" "$FIXTURES/symbolic-or-no-extension-invalid.ssc" \
    >"$sandbox/symbolic-or-no-extension-invalid.$mode.out" \
    2>"$sandbox/symbolic-or-no-extension-invalid.$mode.err"
  symbolic_or_rc=$?
  set -e
  [[ $symbolic_or_rc -ne 0 ]]
  [[ ! -s "$sandbox/symbolic-or-no-extension-invalid.$mode.out" ]]
  grep -F 'expected Int, got "left"' \
    "$sandbox/symbolic-or-no-extension-invalid.$mode.err" >/dev/null
done

for mode in vm asm; do
  mode_args=()
  [[ $mode == asm ]] && mode_args=(--bytecode)
  set +e
  run_native "${mode_args[@]}" "$FIXTURES/dynamic-length-invalid.ssc" \
    >"$sandbox/dynamic-length-invalid.$mode.out" \
    2>"$sandbox/dynamic-length-invalid.$mode.err"
  dynamic_length_rc=$?
  set -e
  [[ $dynamic_length_rc -ne 0 ]]
  [[ ! -s "$sandbox/dynamic-length-invalid.$mode.out" ]]
  grep -F 'no dispatch for .length on true' \
    "$sandbox/dynamic-length-invalid.$mode.err" >/dev/null
done

for mode in vm asm; do
  mode_args=()
  [[ $mode == asm ]] && mode_args=(--bytecode)
  set +e
  run_native "${mode_args[@]}" "$FIXTURES/exact-summon-missing.ssc" \
    >"$sandbox/exact-summon-missing.$mode.out" \
    2>"$sandbox/exact-summon-missing.$mode.err"
  summon_missing_rc=$?
  set -e
  [[ $summon_missing_rc -ne 0 ]]
  [[ ! -s "$sandbox/exact-summon-missing.$mode.out" ]]
  grep -F 'unbound global: summon' \
    "$sandbox/exact-summon-missing.$mode.err" >/dev/null
done

http_port=$((32000 + ($$ % 10000)))
[[ $(run_native "$FIXTURES/http-server-provider.ssc" -- "$http_port") == $'203\npong:/ping' ]]
[[ $(run_native --bytecode "$FIXTURES/http-server-provider.ssc" -- "$((http_port + 1))") == $'203\npong:/ping' ]]
[[ $(PATH="$clean_path" JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$sandbox/java-tmp" SSC_NO_CDS=1 \
  "$ROOT/bin/ssc-tools" run --compat-frontend "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]

[[ $(run_native "$FIXTURES/http-server-fast-feature.ssc") == '' ]]
[[ $(run_native --bytecode "$FIXTURES/http-server-fast-feature.ssc") == '' ]]

imports_vm=$(run_native "$ROOT/examples/imports.ssc")
imports_asm=$(run_native --bytecode "$ROOT/examples/imports.ssc")
[[ "$imports_vm" == "$imports_asm" ]]
grep -F 'triangle perimeter   = 12' <<<"$imports_vm" >/dev/null
grep -F 'square perimeter     = 8' <<<"$imports_vm" >/dev/null
grep -F 'max    : 13' <<<"$imports_vm" >/dev/null

extensions_vm=$(run_native "$ROOT/examples/extensions.ssc")
extensions_asm=$(run_native --bytecode "$ROOT/examples/extensions.ssc")
[[ "$extensions_vm" == "$extensions_asm" ]]
grep -F 'min = 1, max = 9' <<<"$extensions_vm" >/dev/null
grep -F '  Alice: 90' <<<"$extensions_vm" >/dev/null
grep -F '  Carol: 88' <<<"$extensions_vm" >/dev/null

# Object layout now keeps the imported component methods owned, so this
# long-running server example reaches `serve` instead of failing at `unbound s`.
# Start it only long enough to prove the assembled native route reaches the
# listening boundary, then terminate the exact Java process deterministically.
PATH="$clean_path" JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$sandbox/java-tmp" \
  SSC_STORAGE_PATH="$sandbox/storage.json" SSC_NO_CDS=1 \
  "$ROOT/bin/ssc" run --native "$ROOT/examples/components-demo.ssc" \
  >"$sandbox/components.out" 2>"$sandbox/components.err" &
components_pid=$!
components_ready=false
for _ in {1..100}; do
  if grep -F 'Listening on http://localhost:8768/' "$sandbox/components.out" >/dev/null 2>&1; then
    components_ready=true
    break
  fi
  if ! kill -0 "$components_pid" 2>/dev/null; then break; fi
  sleep 0.1
done
kill "$components_pid" 2>/dev/null || true
wait "$components_pid" 2>/dev/null || true
[[ $components_ready == true ]]
if grep -E 'unbound global|parser sentinel _err|File name too long|StackOverflowError' \
  "$sandbox/components.err" >/dev/null; then
  echo 'components native regression before server startup' >&2
  exit 1
fi

for source in graph-fullstack-rdf.ssc; do
  set +e
  run_native "$ROOT/examples/$source" >"$sandbox/$source.out" 2>"$sandbox/$source.err"
  source_rc=$?
  set -e
  [[ $source_rc -ne 0 ]]
  grep -F "$source" "$sandbox/$source.err" >/dev/null
  grep -F 'parser sentinel _err' "$sandbox/$source.err" >/dev/null
  if grep -E 'match: no arm|NoSuchFileException|StackOverflowError' "$sandbox/$source.err" >/dev/null; then
    echo "$source native frontend leaked a host exception" >&2
    exit 1
  fi
done

leaked=$(find "$sandbox/java-tmp" -mindepth 1 -maxdepth 1 \
  \( -name 'sscpkg-*' -o -name 'ssc-v2-plugins*' \) -print -quit)
if [[ -n "$leaked" ]]; then
  echo "native entry temp tree leaked after CLI exit: $leaked" >&2
  exit 1
fi

echo 'v2.1 native entry smoke: PASS'
