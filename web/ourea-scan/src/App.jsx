import React, { useMemo } from 'react'
import TableApp from './TableApp.jsx'
import { LANG } from './i18n.js'
export { LANG }

/*
 * One site, two ways in:
 *   ?t=<table secret>   Ourea Scan -- ordering at the table (TableApp).
 *   ?s=<store link>     Arke -- online ordering for pickup (coming).
 * Both share lib.js (backend, money) and shared.jsx (dish rows and sheets, Beaver).
 */
export default function App() {
  const params = useMemo(() => new URLSearchParams(window.location.search), [])
  return <TableApp token={params.get('t') || ''} />
}
