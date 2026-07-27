#!/usr/bin/env bash
set -euo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
main=scalascript.uniml.dialect.markdown.corpus.MarkdownCorpusGate
platform=${1:-}

usage() {
  echo "usage: uniml/corpus/markdown/run-strict.sh <jvm|js> [--commonmark|--gfm] [--example ID]" >&2
}

case "$platform" in
  jvm)
    shift
    corpus_arg=
    example_arg=
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --commonmark|--gfm)
          if [[ -n "$corpus_arg" ]]; then
            echo "choose at most one corpus filter" >&2
            exit 2
          fi
          corpus_arg=" $1"
          ;;
        --example)
          if [[ -n "$example_arg" || $# -lt 2 ]]; then
            echo "--example requires one positive integer id (at most six digits)" >&2
            exit 2
          fi
          example_id=$2
          if [[ ! "$example_id" =~ ^[0-9]{1,6}$ ]] || ((10#$example_id == 0)); then
            echo "--example requires one positive integer id (at most six digits)" >&2
            exit 2
          fi
          example_arg=" --example $((10#$example_id))"
          shift
          ;;
        *)
          echo "unsupported strict-gate argument: $1" >&2
          usage
          exit 2
          ;;
      esac
      shift
    done
    cd "$repo"
    command="unimlMarkdown/Test/runMain $main$corpus_arg$example_arg"
    if [[ -n "$example_arg" ]]; then
      exec "$repo/scripts/sbtc" "$command"
    else
      # The thin-client transport truncates very large forked-process output.
      # A full gate must retain every failure and the final census in CI logs.
      exec sbt -batch "$command"
    fi
    ;;
  js)
    if [[ $# -ne 1 ]]; then
      echo "Scala.js strict gate accepts no case filters; run the complete corpus." >&2
      exit 2
    fi
    cd "$repo"
    exec sbt -batch \
      "set unimlMarkdownJs / Test / mainClass := Some(\"$main\")" \
      "set unimlMarkdownJs / Test / scalaJSUseTestModuleInitializer := false" \
      "set unimlMarkdownJs / Test / scalaJSUseMainModuleInitializer := true" \
      "unimlMarkdownJs/Test/run"
    ;;
  *)
    usage
    exit 2
    ;;
esac
