#!/usr/bin/env python3
"""Convert Scala 3 indentation-style blocks to brace style for ssc1c.

Handles:
  while COND do       ->  while (COND) {
  def f(): T =        ->  def f(): T = {   (when body is indented block)
  expr match          ->  expr match {     (when arms are indented)
  if COND then        ->  if (COND) {      (when body is indented block)
  else                ->  } else {         (dedent + else)

Closing braces are inserted when indentation decreases below the block level.
"""
import sys, re

def convert(src):
    lines = src.split('\n')
    out = []
    # Stack of (indent_level, needs_close_brace)
    # indent_level = indent of the line that OPENED the block
    stack = []

    i = 0
    while i < len(lines):
        raw = lines[i]
        stripped = raw.lstrip()

        # Empty / comment lines: pass through unchanged
        if not stripped or stripped.startswith('//') or stripped.startswith('/*') or stripped.startswith('*'):
            out.append(raw)
            i += 1
            continue

        indent = len(raw) - len(stripped)

        # Close blocks that end before or at this indent
        while stack and indent <= stack[-1]:
            out.append(' ' * stack[-1] + '}')
            stack.pop()

        # Peek at next non-empty line to decide if this line opens an indented block
        j = i + 1
        while j < len(lines) and not lines[j].strip():
            j += 1
        next_line = lines[j] if j < len(lines) else ''
        next_stripped = next_line.lstrip()
        next_indent = len(next_line) - len(next_stripped) if next_stripped else -1
        opens_block = next_indent > indent

        if opens_block:
            # Transform line to explicitly open a brace block
            # while COND do  ->  while (COND) {
            # ssc1-front accepts unparenthesized while conditions, but a
            # following brace block can otherwise be parsed as an argument to
            # the last condition atom: while i < 1000 { body }.
            m = re.match(r'^(\s*)while\s+(.+?)\s+do\s*$', raw)
            if m:
                out.append(f'{m.group(1)}while ({m.group(2)}) {{')
                stack.append(indent)
                i += 1
                continue

            # expr match  ->  expr match {
            if stripped.rstrip().endswith('match'):
                out.append(raw + ' {')
                stack.append(indent)
                i += 1
                continue

            # def f(): T =  ->  def f(): T = {
            # (but not val x = 5, only when body is multi-stmt)
            if re.match(r'^\s*def\b', raw) and stripped.rstrip().endswith('='):
                out.append(raw + ' {')
                stack.append(indent)
                i += 1
                continue

            # if COND then  ->  if COND then {  (only if body is indented block)
            if re.match(r'^\s*\bif\b', raw) and stripped.rstrip().endswith(' then'):
                out.append(raw + ' {')
                stack.append(indent)
                i += 1
                continue

            # else  ->  } else {  handled below; plain `else` on own line
            if stripped.rstrip() == 'else':
                # The previous block was closed already by the dedent logic above
                out.append(raw + ' {')
                stack.append(indent)
                i += 1
                continue

            # Fallback: if none of the above, just open a brace
            # Skip: multi-line function calls (line ends with '('), chained calls,
            # or next line is a method continuation starting with '.'
            s = stripped.rstrip()
            if not s.endswith('(') and not s.endswith(')') and not next_stripped.startswith('.'):
                out.append(raw + ' {')
                stack.append(indent)
            else:
                out.append(raw)
        else:
            out.append(raw)

        i += 1

    # Close remaining open blocks
    while stack:
        out.append(' ' * stack[-1] + '}')
        stack.pop()

    return '\n'.join(out)


if __name__ == '__main__':
    src = sys.stdin.read()
    print(convert(src))
