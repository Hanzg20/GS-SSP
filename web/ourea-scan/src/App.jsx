import React, { useMemo } from 'react'
import TableApp from './TableApp.jsx'
import ArkeApp from './ArkeApp.jsx'
import { LANG, T } from './i18n.js'
import { AK } from './arke-i18n.js'
export { LANG }

/*
 * One site, two ways in:
 *   ?t=<table secret>   Ourea Scan -- ordering at the table (TableApp).
 *   ?s=<store link>     Arke -- online ordering for pickup (ArkeApp).
 *   ?o=<order token>    Arke -- one online order's own page.
 * Both share lib.js (backend, money) and shared.jsx (dish rows and sheets).
 * Arke is not at a table: the few shared words that speak of a server / the
 * table's menu are swapped for its own (AK.shared) before anything renders.
 */
export default function App() {
  const params = useMemo(() => new URLSearchParams(window.location.search), [])
  if (params.get('s') || params.get('o')) {
    Object.assign(T, AK.shared)
    if (document.title === 'Ourea Scan') document.title = 'Arke'
    return <ArkeApp slug={params.get('s') || ''} orderToken={params.get('o') || ''} />
  }
  return <TableApp token={params.get('t') || ''} />
}
