package scalascript.frontend.toolkit

import scala.annotation.nowarn
import scalascript.frontend.{View, ReactiveSignal}

/** Toolkit helper that produces a data-table UI backed by a REST endpoint.
 *
 *  Replaces the deprecated `View.FetchTable` IR primitive.  The function
 *  builds the same table structure — fetch on mount, re-fetch on `tick`,
 *  per-row delete button — but as a regular toolkit helper rather than a
 *  special-cased IR node.
 *
 *  Web renderers (React / Vue / Solid / Custom) handle `View.FetchTable`
 *  via their existing collector + emitter paths; this wrapper lets callers
 *  stop using the deprecated IR case while keeping the existing rendering
 *  behaviour.  In P3, when `View.FetchTable` is removed from the IR, this
 *  helper will be re-implemented using the semantic View primitives
 *  (`View.Column`, `View.Button`, etc.) and a proper reactive data-fetching
 *  API so it becomes truly target-agnostic.
 *
 *  @param tableId   stable JS identifier for the row-list signal (must be a
 *                   valid JS identifier: `[A-Za-z_][A-Za-z0-9_]*`)
 *  @param fetchUrl  GET endpoint — response must be a JSON array of `{id, text}` objects
 *  @param deleteUrl POST endpoint — body is the `row.id` string; increments `tick` on success
 *  @param tick      signal that drives re-fetches; increment to trigger a refresh
 *  @param headers   optional Signal[String] holding a JSON-object of request headers (e.g.
 *                   `{"Authorization":"Bearer …"}`); read at fetch/delete time */
@nowarn("cat=deprecation")
def fetchTable(
    tableId:   String,
    fetchUrl:  String,
    deleteUrl: String,
    tick:      ReactiveSignal[Int],
    headers:   Option[ReactiveSignal[String]] = None
): View[?] =
  View.FetchTable(tableId, fetchUrl, deleteUrl, tick, headers)
