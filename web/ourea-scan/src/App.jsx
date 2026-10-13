import React, { useState, useEffect } from 'react'
import { createClient } from '@supabase/supabase-js'
import { ShoppingCart, Plus, Minus, CheckCircle, ChevronRight, X, Utensils, Edit3, MapPin } from 'lucide-react'

// Initialize Supabase Client
const SUPABASE_URL = "https://ttbtxxpnvkcbyugzdqfw.supabase.co"
const SUPABASE_KEY = "sb_publishable_g4TGLHlVn3LfafRZFOAjnA_x9M0xyBi"
const supabase = createClient(SUPABASE_URL, SUPABASE_KEY)

export default function App() {
  const [orgId, setOrgId] = useState("426431a4-8f4b-4e8e-afe1-9a0e67907c3e")
  const [tableId, setTableId] = useState("")
  const [tableName, setTableName] = useState("Table 4")
  const [showTableModal, setShowTableModal] = useState(false)
  const [customTableName, setCustomTableName] = useState("")

  const [products, setProducts] = useState([])
  const [categories, setCategories] = useState(["All"])
  const [activeCategory, setActiveCategory] = useState("All")
  const [cart, setCart] = useState([]) // [{ product, quantity, note }]
  const [isCartOpen, setIsCartOpen] = useState(false)
  const [orderSubmitted, setOrderSubmitted] = useState(false)
  const [loading, setLoading] = useState(true)

  // Item note modal state
  const [notingItem, setNotingItem] = useState(null)
  const [itemNote, setItemNote] = useState("")

  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const oId = params.get('org_id')
    const tId = params.get('table_id')
    const tName = params.get('table_name')
    if (oId) setOrgId(oId)
    if (tId) setTableId(tId)
    if (tName) setTableName(tName)
    if (!tId) {
      setShowTableModal(true)
    }

    fetchMenu(oId || orgId)
  }, [])

  const fetchMenu = async (currentOrgId) => {
    setLoading(true)
    try {
      const { data, error } = await supabase
        .from('products')
        .select('*')
        .eq('org_id', currentOrgId)

      if (error) throw error
      if (data && data.length > 0) {
        setProducts(data)
        const cats = ["All", ...new Set(data.map(p => p.category || "Mains"))]
        setCategories(cats)
      } else {
        const mockProducts = [
          { id: '1', name: 'Truffle Wagyu Burger', price_cents: 1800, category: 'Mains', description: 'Juicy wagyu beef patty with black truffle mayo & artisan brioche.' },
          { id: '2', name: 'Artisan Margherita Pizza', price_cents: 1550, category: 'Mains', description: 'San Marzano tomatoes, fresh fior di latte mozzarella & basil.' },
          { id: '3', name: 'Crispy Calamari', price_cents: 1200, category: 'Appetizers', description: 'Lightly battered squid rings served with zesty lemon aioli.' },
          { id: '4', name: 'Signature Cold Brew', price_cents: 550, category: 'Drinks', description: '12-hour slow steeped single-origin coffee beans.' },
          { id: '5', name: 'Molten Lava Chocolate Cake', price_cents: 900, category: 'Desserts', description: 'Rich chocolate cake with a warm gooey center and vanilla gelato.' }
        ]
        setProducts(mockProducts)
        setCategories(["All", "Mains", "Appetizers", "Drinks", "Desserts"])
      }
    } catch (err) {
      console.error("Error fetching menu:", err)
    } finally {
      setLoading(false)
    }
  }

  const addToCart = (product) => {
    setCart(prev => {
      const existing = prev.find(item => item.product.id === product.id && !item.note)
      if (existing) {
        return prev.map(item => (item.product.id === product.id && !item.note) ? { ...item, quantity: item.quantity + 1 } : item)
      }
      return [...prev, { product, quantity: 1, note: "" }]
    })
  }

  const updateQuantity = (index, delta) => {
    setCart(prev => {
      return prev.map((item, idx) => {
        if (idx === index) {
          const newQty = item.quantity + delta
          return newQty > 0 ? { ...item, quantity: newQty } : null
        }
        return item
      }).filter(Boolean)
    })
  }

  const saveItemNote = () => {
    if (notingItem !== null) {
      setCart(prev => prev.map((item, idx) => idx === notingItem ? { ...item, note: itemNote } : item))
      setNotingItem(null)
      setItemNote("")
    }
  }

  const subtotalCents = cart.reduce((sum, item) => sum + (item.product.price_cents * item.quantity), 0)
  const taxCents = Math.round(subtotalCents * 0.13)
  const totalCents = subtotalCents + taxCents
  const totalItems = cart.reduce((sum, item) => sum + item.quantity, 0)

  const submitOrder = async () => {
    if (cart.length === 0) return
    try {
      // In production, insert into Supabase dine_check_items here
      setOrderSubmitted(true)
      setCart([])
    } catch (err) {
      console.error("Order submission failed:", err)
      alert("Failed to submit order. Please try again.")
    }
  }

  if (orderSubmitted) {
    return (
      <div className="min-h-screen bg-slate-900 text-white flex flex-col items-center justify-center p-6 text-center">
        <CheckCircle className="w-20 h-20 text-emerald-400 mb-6 animate-bounce" />
        <h1 className="text-3xl font-black mb-2">Order Sent to Kitchen!</h1>
        <p className="text-slate-400 max-w-sm mb-8">Your order has been successfully placed for {tableName}. Our chef is preparing your dishes right now.</p>
        <button
          onClick={() => setOrderSubmitted(false)}
          className="bg-emerald-500 hover:bg-emerald-600 font-bold px-8 py-3 rounded-full shadow-lg transition-all"
        >
          Order More Items
        </button>
      </div>
    )
  }

  const filteredProducts = activeCategory === "All" ? products : products.filter(p => (p.category || "Mains") === activeCategory)

  return (
    <div className="min-h-screen pb-28">
      {/* Table Selection Modal if no table ID */}
      {showTableModal && (
        <div className="fixed inset-0 z-50 bg-slate-900/80 backdrop-blur-xs flex items-center justify-center p-4">
          <div className="bg-white w-full max-w-md rounded-3xl p-6 shadow-2xl space-y-4 text-center">
            <div className="bg-emerald-100 text-emerald-800 w-14 h-14 rounded-full flex items-center justify-center mx-auto mb-2">
              <MapPin className="w-7 h-7" />
            </div>
            <h2 className="font-black text-2xl text-slate-900">Enter Your Table Number</h2>
            <p className="text-sm text-slate-500">Please enter your table or seat identifier to start ordering.</p>
            <input
              type="text"
              placeholder="e.g. Table 4 or Patio A"
              value={customTableName}
              onChange={(e) => setCustomTableName(e.target.value)}
              className="w-full px-4 py-3 rounded-xl border border-slate-300 font-bold text-center text-lg focus:outline-emerald-500"
            />
            <button
              onClick={() => {
                if (customTableName.trim()) {
                  setTableName(customTableName.trim())
                  setTableId("custom-" + Date.now())
                  setShowTableModal(false)
                }
              }}
              className="w-full bg-slate-900 hover:bg-slate-800 text-white font-bold py-3.5 rounded-xl shadow-md transition-all text-base"
            >
              Start Ordering
            </button>
          </div>
        </div>
      )}

      {/* Top Header */}
      <header className="sticky top-0 z-30 bg-white/90 backdrop-blur-md border-b border-slate-200 px-4 py-3 flex items-center justify-between shadow-xs">
        <div className="flex items-center space-x-2">
          <div className="bg-slate-900 text-white p-2 rounded-xl">
            <Utensils className="w-5 h-5" />
          </div>
          <div>
            <h1 className="font-black text-lg tracking-tight text-slate-900">Ourea Scan</h1>
            <p className="text-xs text-slate-500">Table Ordering & Menu</p>
          </div>
        </div>
        <div
          onClick={() => setShowTableModal(true)}
          className="bg-emerald-100 text-emerald-800 font-bold text-xs px-3 py-1.5 rounded-full border border-emerald-200 shadow-2xs cursor-pointer hover:bg-emerald-200 transition-all flex items-center space-x-1"
        >
          <MapPin className="w-3.5 h-3.5" />
          <span>{tableName}</span>
        </div>
      </header>

      {/* Category Pills Bar */}
      <nav className="sticky top-[57px] z-20 bg-slate-50/90 backdrop-blur-md px-4 py-2.5 overflow-x-auto flex space-x-2 border-b border-slate-200 no-scrollbar">
        {categories.map(cat => (
          <button
            key={cat}
            onClick={() => setActiveCategory(cat)}
            className={`px-4 py-2 rounded-full text-sm font-bold whitespace-nowrap transition-all ${
              activeCategory === cat
                ? 'bg-slate-900 text-white shadow-md'
                : 'bg-white text-slate-600 hover:bg-slate-100 border border-slate-200'
            }`}
          >
            {cat}
          </button>
        ))}
      </nav>

      {/* Product Grid */}
      <main className="max-w-3xl mx-auto p-4">
        {loading ? (
          <div className="flex justify-center items-center h-64 text-slate-400 font-medium">Loading Menu...</div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            {filteredProducts.map(product => (
              <div key={product.id} className="bg-white rounded-2xl p-4 border border-slate-200 shadow-xs flex flex-col justify-between hover:shadow-md transition-all">
                <div>
                  <h3 className="font-bold text-slate-900 text-base mb-1">{product.name}</h3>
                  <p className="text-xs text-slate-500 line-clamp-2 mb-4">{product.description || 'Freshly prepared with premium ingredients.'}</p>
                </div>
                <div className="flex items-center justify-between pt-2 border-t border-slate-100">
                  <span className="font-black text-slate-900 text-lg">${(product.price_cents / 100).toFixed(2)}</span>
                  <button
                    onClick={() => addToCart(product)}
                    className="bg-slate-900 hover:bg-slate-800 text-white p-2.5 rounded-xl shadow-xs active:scale-95 transition-all flex items-center justify-center"
                  >
                    <Plus className="w-5 h-5" />
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </main>

      {/* Floating Bottom Cart Bar */}
      {totalItems > 0 && (
        <div className="fixed bottom-4 left-4 right-4 z-40 max-w-xl mx-auto">
          <div
            onClick={() => setIsCartOpen(true)}
            className="bg-slate-900 text-white px-6 py-4 rounded-2xl shadow-xl flex items-center justify-between cursor-pointer active:scale-[0.98] transition-all"
          >
            <div className="flex items-center space-x-3">
              <div className="bg-emerald-500 text-white font-bold w-7 h-7 rounded-full flex items-center justify-center text-sm shadow-inner">
                {totalItems}
              </div>
              <span className="font-bold">View Order & Summary</span>
            </div>
            <div className="flex items-center space-x-2">
              <span className="font-black text-lg">${(totalCents / 100).toFixed(2)}</span>
              <ChevronRight className="w-5 h-5 text-slate-400" />
            </div>
          </div>
        </div>
      )}

      {/* Bottom Sheet Cart Modal */}
      {isCartOpen && (
        <div className="fixed inset-0 z-50 bg-slate-900/60 backdrop-blur-xs flex items-end sm:items-center sm:justify-center p-0 sm:p-4">
          <div className="bg-white w-full max-w-lg rounded-t-3xl sm:rounded-3xl max-h-[90vh] flex flex-col shadow-2xl overflow-hidden animate-in fade-in slide-in-from-bottom duration-200">
            {/* Modal Header */}
            <div className="px-6 py-4 border-b border-slate-100 flex items-center justify-between">
              <h2 className="font-black text-xl text-slate-900">Your Current Order ({tableName})</h2>
              <button onClick={() => setIsCartOpen(false)} className="p-2 text-slate-400 hover:text-slate-600 rounded-full">
                <X className="w-6 h-6" />
              </button>
            </div>

            {/* Cart Items List */}
            <div className="p-6 overflow-y-auto flex-1 space-y-4">
              {cart.map((item, idx) => (
                <div key={idx} className="flex flex-col py-3 border-b border-slate-100 last:border-0">
                  <div className="flex items-center justify-between">
                    <div className="flex-1 pr-4">
                      <h4 className="font-bold text-slate-900">{item.product.name}</h4>
                      <p className="text-xs text-slate-500">${(item.product.price_cents / 100).toFixed(2)} each</p>
                      {item.note && <p className="text-xs text-emerald-600 italic mt-1">Note: {item.note}</p>}
                    </div>
                    <div className="flex items-center space-x-3">
                      <button
                        onClick={() => updateQuantity(idx, -1)}
                        className="w-8 h-8 rounded-full bg-slate-100 text-slate-700 flex items-center justify-center font-bold hover:bg-slate-200"
                      >
                        <Minus className="w-4 h-4" />
                      </button>
                      <span className="font-bold text-slate-900 w-4 text-center">{item.quantity}</span>
                      <button
                        onClick={() => updateQuantity(idx, 1)}
                        className="w-8 h-8 rounded-full bg-slate-900 text-white flex items-center justify-center font-bold hover:bg-slate-800"
                      >
                        <Plus className="w-4 h-4" />
                      </button>
                    </div>
                  </div>
                  <button
                    onClick={() => { setNotingItem(idx); setItemNote(item.note || ""); }}
                    className="text-xs text-slate-400 hover:text-slate-600 flex items-center space-x-1 mt-2 self-start"
                  >
                    <Edit3 className="w-3 h-3" />
                    <span>{item.note ? 'Edit instruction' : 'Add kitchen note'}</span>
                  </button>
                </div>
              ))}
            </div>

            {/* Summary & Checkout Footer */}
            <div className="p-6 bg-slate-50 border-t border-slate-200 space-y-3">
              <div className="flex justify-between text-sm text-slate-600">
                <span>Subtotal</span>
                <span className="font-bold">${(subtotalCents / 100).toFixed(2)}</span>
              </div>
              <div className="flex justify-between text-sm text-slate-600">
                <span>Estimated Tax (13%)</span>
                <span className="font-bold">${(taxCents / 100).toFixed(2)}</span>
              </div>
              <div className="flex justify-between text-lg font-black text-slate-900 pt-2 border-t border-slate-200">
                <span>Total</span>
                <span>${(totalCents / 100).toFixed(2)}</span>
              </div>
              <button
                onClick={() => { setIsCartOpen(false); submitOrder(); }}
                className="w-full mt-4 bg-emerald-500 hover:bg-emerald-600 text-white font-black py-4 rounded-2xl shadow-lg transition-all text-center text-lg active:scale-[0.99]"
              >
                Send Order to Kitchen 🚀
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Item Note Modal */}
      {notingItem !== null && (
        <div className="fixed inset-0 z-50 bg-slate-900/60 backdrop-blur-xs flex items-center justify-center p-4">
          <div className="bg-white w-full max-w-md rounded-3xl p-6 shadow-2xl space-y-4">
            <h3 className="font-black text-xl text-slate-900">Kitchen Instructions</h3>
            <p className="text-xs text-slate-500">Add special requests (e.g. "No onions", "Extra spicy")</p>
            <input
              type="text"
              placeholder="e.g. Dressing on the side"
              value={itemNote}
              onChange={(e) => setItemNote(e.target.value)}
              className="w-full px-4 py-3 rounded-xl border border-slate-300 font-medium text-base focus:outline-emerald-500"
            />
            <div className="flex space-x-3 pt-2">
              <button
                onClick={() => setNotingItem(null)}
                className="flex-1 bg-slate-100 hover:bg-slate-200 text-slate-700 font-bold py-3 rounded-xl transition-all"
              >
                Cancel
              </button>
              <button
                onClick={saveItemNote}
                className="flex-1 bg-slate-900 hover:bg-slate-800 text-white font-bold py-3 rounded-xl transition-all shadow-md"
              >
                Save Note
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
