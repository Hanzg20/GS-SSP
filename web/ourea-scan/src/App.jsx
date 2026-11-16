import React, { useMemo } from 'react'
import TableApp from './TableApp.jsx'
import ArkeApp from './ArkeApp.jsx'
import { LANG } from './i18n.js'
export { LANG }

/*
 * One site, two ways in:
 *   ?t=<table secret>   Ourea Scan -- ordering at the table (TableApp).
 *   ?s=<store link>     Arke -- online ordering for pickup (ArkeApp).
 *   ?o=<order token>    Arke -- one online order's own page.
 * Both share lib.js (backend, money) and shared.jsx (dish rows and sheets, Beaver).
 */
export default function App() {
  const params = useMemo(() => new URLSearchParams(window.location.search), [])
  if (params.get('s') || params.get('o')) return <ArkeApp slug={params.get('s') || ''} orderToken={params.get('o') || ''} />
  return <TableApp token={params.get('t') || ''} />
}
