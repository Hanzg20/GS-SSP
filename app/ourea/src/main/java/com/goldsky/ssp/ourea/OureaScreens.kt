package com.goldsky.ssp.ourea

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.goldsky.ssp.db.OrderEntity
import com.goldsky.ssp.db.ParkedOrderEntity
import com.goldsky.ssp.feature.retail.RetailRepository
import com.goldsky.ssp.feature.retail.viewmodel.RetailViewModel
import com.goldsky.ssp.model.CartItem
import com.goldsky.ssp.model.Product
import com.goldsky.ssp.payment.TransactionRepository
import com.goldsky.ssp.ui.components.NumericKeypad
import com.goldsky.ssp.core.ui.R as CoreUiR
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Which of the sidebar's wired-up destinations is currently showing. */
enum class OureaDestination { CHECKOUT, INSIGHTS, PRODUCTS, TRANSACTIONS, SETTINGS }

/**
 * Left icon rail modeled on the reference screenshots' sidebar. Only wired
 * to destinations that already have a real screen behind them (Checkout,
 * Insights, Transactions, Settings) -- the reference also shows discount/
 * mail/notification icons with no corresponding feature in this codebase,
 * deliberately left out rather than built as dead UI.
 */
@Composable
fun OureaSidebar(current: OureaDestination, onSelect: (OureaDestination) -> Unit, onLock: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(72.dp)
            .background(OureaSidebar)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(CoreUiR.drawable.goldsky_logo),
            contentDescription = "GoldSky",
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(32.dp))
        SidebarIcon(Icons.Default.Dashboard, current == OureaDestination.INSIGHTS) { onSelect(OureaDestination.INSIGHTS) }
        Spacer(modifier = Modifier.height(20.dp))
        SidebarIcon(Icons.Default.ShoppingCart, current == OureaDestination.CHECKOUT) { onSelect(OureaDestination.CHECKOUT) }
        Spacer(modifier = Modifier.height(20.dp))
        SidebarIcon(Icons.Default.Inventory2, current == OureaDestination.PRODUCTS) { onSelect(OureaDestination.PRODUCTS) }
        Spacer(modifier = Modifier.height(20.dp))
        SidebarIcon(Icons.Default.History, current == OureaDestination.TRANSACTIONS) { onSelect(OureaDestination.TRANSACTIONS) }
        Spacer(modifier = Modifier.weight(1f))
        SidebarIcon(Icons.Default.Settings, current == OureaDestination.SETTINGS) { onSelect(OureaDestination.SETTINGS) }
        Spacer(modifier = Modifier.height(20.dp))
        SidebarIcon(Icons.Default.Lock, false, onLock)
    }
}

/**
 * Selected state is a soft surface tint + gold icon, not a solid color-block
 * fill -- reads as refined/premium rather than a neon "AI app" highlight.
 */
@Composable
private fun SidebarIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) OureaSurfaceVariant else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, null, tint = if (selected) OureaGold else OureaTextSecondary)
        }
    }
}

/** Primary CTA using the logo's gold-to-amber sweep instead of a flat fill. */
@Composable
fun OureaGradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) OureaBrandGradient else Brush.horizontalGradient(listOf(OureaSurfaceVariant, OureaSurfaceVariant)))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = Color(0xFF231A00))
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text(text, color = Color(0xFF231A00), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

/**
 * Product grid (center) + cart panel (right) -- same data/state as
 * app/iris's checkout flow (RetailRepository.catalog, RetailViewModel),
 * new visual layout only. No product images are wired (Product.image_url
 * is usually null -- no real catalog images exist yet); cards use a
 * placeholder icon instead of fabricating stock photography like the
 * reference mockups show.
 */
@Composable
fun OureaCheckoutScreen(viewModel: RetailViewModel, onCheckout: (Int) -> Unit) {
    val context = LocalContext.current
    val catalog by RetailRepository.catalog.collectAsState()
    val cart = viewModel.cart
    val total by viewModel.totalCents.collectAsState()
    val parkedOrders by viewModel.parkedOrders.collectAsState()
    var query by remember { mutableStateOf("") }
    var showHeldOrders by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<String?>(null) } // null = "All"
    val categories = remember(catalog) { catalog.mapNotNull { it.category }.distinct().sorted() }
    val filtered = remember(catalog, query, selectedCategory) {
        catalog.filter { product ->
            (query.isBlank() || product.name.contains(query, ignoreCase = true)) &&
                (selectedCategory == null || product.category == selectedCategory)
        }
    }

    LaunchedEffect(Unit) { viewModel.loadParkedOrders(context) }

    Row(modifier = Modifier.fillMaxSize()) {
        // Cart panel
        Column(
            modifier = Modifier
                .width(320.dp)
                .fillMaxHeight()
                .background(OureaSurface)
                .padding(16.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Cart", color = OureaTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (parkedOrders.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(OureaSurfaceVariant)
                            .clickable { showHeldOrders = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Receipt, null, tint = OureaSkyBlue, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Held (${parkedOrders.size})", color = OureaTextPrimary, fontSize = 13.sp)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(cart) { item ->
                    CartRow(
                        item,
                        onIncrement = { viewModel.addItem(item.product, item.selectedModifiers) },
                        onDecrement = { viewModel.removeItem(item.id) }
                    )
                }
            }
            Divider(color = OureaSurfaceVariant)
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total", color = OureaTextSecondary)
                Text("$${"%.2f".format(total / 100.0)}", color = OureaTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (cart.isNotEmpty()) {
                OutlinedButton(
                    onClick = { viewModel.parkCurrentOrder(context) },
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Icon(Icons.Default.Schedule, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Hold Order")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            OureaGradientButton(
                text = "CHECKOUT",
                onClick = { if (total > 0) onCheckout(total) },
                enabled = total > 0,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            )
        }

        if (showHeldOrders) {
            OureaHeldOrdersDialog(
                orders = parkedOrders,
                onResume = { entity ->
                    showHeldOrders = false
                    viewModel.resumeOrder(context, entity)
                },
                onDismiss = { showHeldOrders = false }
            )
        }

        // Product grid
        Column(modifier = Modifier.weight(1f).fillMaxHeight().background(OureaBackground).padding(16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search products", color = OureaTextSecondary) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = OureaTextSecondary) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = OureaSurface,
                    unfocusedContainerColor = OureaSurface,
                    focusedTextColor = OureaTextPrimary,
                    unfocusedTextColor = OureaTextPrimary,
                    focusedBorderColor = OureaSkyBlue,
                    unfocusedBorderColor = OureaSurfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No products yet -- add some from the CMP back office", color = OureaTextSecondary)
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 160.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(filtered) { product -> ProductCard(product) { viewModel.addItem(product) } }
                }
            }
        }

        // Category rail (reference screenshots' right-side tab list) -- only
        // rendered once at least one product actually has a category (see
        // Product.category's doc comment: it rides in attributes JSONB and
        // is null until something sets it), so an uncategorized catalog
        // looks the same as before rather than showing a rail with only "All".
        if (categories.isNotEmpty()) {
            OureaCategoryRail(categories = categories, selected = selectedCategory, onSelect = { selectedCategory = it })
        }
    }
}

/**
 * Read-only product/inventory browsing (reference screenshots' "产品管理"
 * screen). Deliberately NOT add/edit/delete: `public.products`' RLS only
 * grants INSERT/UPDATE to `authenticated` users with `catalog.manage`
 * permission (a real human CMP login) -- Ourea's device authenticates
 * anonymously and has no write policy at all, by design (a POS terminal
 * shouldn't be able to rewrite the shared price catalog). Real catalog
 * management belongs in gs-ssp-cmp, which already has the permission model
 * for it (just no UI yet) -- see docs/system_architecture.md for the
 * decision trail. This screen only surfaces what's already synced: browse/
 * search/filter by category, see stock levels where CMP has set them.
 */
@Composable
fun OureaProductsScreen() {
    val catalog by RetailRepository.catalog.collectAsState()
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    val categories = remember(catalog) { catalog.mapNotNull { it.category }.distinct().sorted() }
    val filtered = remember(catalog, query, selectedCategory) {
        catalog.filter { product ->
            (query.isBlank() || product.name.contains(query, ignoreCase = true)) &&
                (selectedCategory == null || product.category == selectedCategory)
        }
    }
    val outOfStockCount = remember(catalog) { catalog.count { it.stockQty == 0 } }
    val lowStockCount = remember(catalog) { catalog.count { (it.stockQty ?: Int.MAX_VALUE) in 1..5 } }

    Row(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight().background(OureaBackground).padding(16.dp)) {
            Text("Products", color = OureaTextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${catalog.size} products", color = OureaTextSecondary, fontSize = 13.sp)
                if (lowStockCount > 0) {
                    Text("  ·  ", color = OureaTextSecondary, fontSize = 13.sp)
                    Text("$lowStockCount low stock", color = OureaGold, fontSize = 13.sp)
                }
                if (outOfStockCount > 0) {
                    Text("  ·  ", color = OureaTextSecondary, fontSize = 13.sp)
                    Text("$outOfStockCount out of stock", color = OureaRed, fontSize = 13.sp)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search products", color = OureaTextSecondary) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = OureaTextSecondary) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = OureaSurface,
                    unfocusedContainerColor = OureaSurface,
                    focusedTextColor = OureaTextPrimary,
                    unfocusedTextColor = OureaTextPrimary,
                    focusedBorderColor = OureaSkyBlue,
                    unfocusedBorderColor = OureaSurfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No products yet -- add some from the CMP back office", color = OureaTextSecondary)
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 160.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(filtered) { product -> ProductCard(product) }
                }
            }
        }

        if (categories.isNotEmpty()) {
            OureaCategoryRail(categories = categories, selected = selectedCategory, onSelect = { selectedCategory = it })
        }
    }
}

@Composable
private fun OureaCategoryRail(categories: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    Column(
        modifier = Modifier
            .width(130.dp)
            .fillMaxHeight()
            .background(OureaSurface)
            .padding(vertical = 16.dp, horizontal = 8.dp)
    ) {
        OureaCategoryRailItem("All", selected == null) { onSelect(null) }
        Spacer(modifier = Modifier.height(4.dp))
        categories.forEach { category ->
            OureaCategoryRailItem(category, selected == category) { onSelect(category) }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun OureaCategoryRailItem(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) OureaSurfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp)
    ) {
        Text(
            label,
            color = if (isSelected) OureaGold else OureaTextSecondary,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * onDecrement reuses RetailViewModel.removeItem, which drops to full
 * removal (not qty 0) at qty=1 -- so "-" at qty=1 behaves as delete, same as
 * the old single X button did, just also exposed as an explicit "+" now.
 */
@Composable
private fun CartRow(item: CartItem, onIncrement: () -> Unit, onDecrement: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.product.name, color = OureaTextPrimary, fontWeight = FontWeight.Medium)
            Text("$${"%.2f".format(item.unitPriceCents / 100.0)} each", color = OureaTextSecondary, fontSize = 12.sp)
        }
        Text("$${"%.2f".format(item.totalPriceCents / 100.0)}", color = OureaTextPrimary)
        Spacer(modifier = Modifier.width(10.dp))
        Row(
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(OureaSurfaceVariant),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDecrement, modifier = Modifier.size(28.dp)) {
                Icon(if (item.quantity > 1) Icons.Default.Remove else Icons.Default.Close, null, tint = OureaTextSecondary, modifier = Modifier.size(16.dp))
            }
            Text("${item.quantity}", color = OureaTextPrimary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
            IconButton(onClick = onIncrement, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Add, null, tint = OureaSkyBlue, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ProductCard(product: Product, onAdd: (() -> Unit)? = null) {
    val outOfStock = product.stockQty == 0
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(OureaSurface)
            .padding(14.dp)
            .let { if (outOfStock) it.alpha(0.5f) else it }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(OureaSurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Inventory2, null, tint = OureaTextSecondary)
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(product.name, color = OureaTextPrimary, fontWeight = FontWeight.Medium, maxLines = 2)
        product.category?.let { Text(it, color = OureaTextSecondary, fontSize = 11.sp) }
        product.stockQty?.let { OureaStockBadge(it, Modifier.padding(top = 4.dp)) }
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("$${"%.2f".format(product.price_cents / 100.0)}", color = OureaGold, fontWeight = FontWeight.Bold)
            // onAdd == null: read-only context (Products/inventory screen) -- no add-to-cart affordance at all,
            // rather than a "+" button that does nothing.
            if (onAdd != null) {
                IconButton(onClick = onAdd, enabled = !outOfStock, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.AddCircle, null, tint = if (outOfStock) OureaTextSecondary else OureaSkyBlue)
                }
            }
        }
    }
}

/**
 * Reads Product.stockQty (rides in `attributes.stock_qty`, see that
 * property's doc comment -- no products have this set yet unless someone
 * seeds it via SQL, same as category). Threshold of 5 for "low" is a
 * reasonable default, not a value WizarPOS/CMP has specified anywhere.
 */
@Composable
private fun OureaStockBadge(stockQty: Int, modifier: Modifier = Modifier) {
    val (label, color) = when {
        stockQty <= 0 -> "Out of Stock" to OureaRed
        stockQty <= 5 -> "Low: $stockQty left" to OureaGold
        else -> "$stockQty in stock" to OureaTextSecondary
    }
    Box(
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Bill-breakdown + charge screen shown after the tip step, styled after the
 * reference's payment-confirmation mockup. All four of the reference's
 * payment methods are now wired to real backend logic: card-present (via
 * PaymentProviderFactory, unchanged), cash (staff-attested, recorded
 * directly as PAID), QR (QrPaymentRepository -- the same Supabase-backed
 * scan-to-pay session/poll feature/wash's kiosk flow uses), and member card
 * (VipRepository, manually-entered code instead of an NFC tap -- see
 * MainActivity.triggerVipPayment's doc comment for why). None of these are
 * non-functional decoration anymore.
 */
@Composable
fun OureaPaymentScreen(
    subtotalCents: Int,
    taxCents: Int,
    tipCents: Int,
    onChargeCard: () -> Unit,
    onCash: () -> Unit,
    onQr: () -> Unit,
    onMember: () -> Unit,
    onBack: () -> Unit
) {
    val total = subtotalCents + taxCents + tipCents
    Row(modifier = Modifier.fillMaxSize().background(OureaBackground).padding(24.dp)) {
        // widthIn(max=) rather than a bare fixed width: fillMaxWidth() below sizes every
        // button to whatever this column actually gets, so they never overflow this weight(1f)
        // pane's real width the way a hard-coded 280.dp on each button previously could (visually
        // confirmed on-device: at this column's actual measured width, fixed-width buttons
        // rendered past the column's right edge and were hidden behind the Bill Details panel).
        Column(modifier = Modifier.weight(1f).widthIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            Text("Confirm Payment", color = OureaTextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(24.dp))
            OureaGradientButton(
                text = "Charge Card",
                onClick = onChargeCard,
                icon = Icons.Default.CreditCard,
                modifier = Modifier.fillMaxWidth().height(72.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            OureaPaymentMethodButton("Cash", Icons.Default.Payments, onCash, Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(12.dp))
            OureaPaymentMethodButton("QR Code", Icons.Default.QrCode, onQr, Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(12.dp))
            OureaPaymentMethodButton("Member Card", Icons.Default.Badge, onMember, Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back to Order")
            }
        }
        Column(
            modifier = Modifier
                .width(340.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(16.dp))
                .background(OureaSurface)
                .padding(20.dp)
        ) {
            Text("Bill Details", color = OureaTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            BillLine("Subtotal", subtotalCents, OureaTextSecondary)
            BillLine("Tax", taxCents, OureaTextSecondary)
            if (tipCents > 0) BillLine("Tip", tipCents, OureaTextSecondary)
            Divider(color = OureaSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total Due", color = OureaTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("$${"%.2f".format(total / 100.0)}", color = OureaGold, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun BillLine(label: String, cents: Int, labelColor: Color) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = labelColor)
        Text("$${"%.2f".format(cents / 100.0)}", color = OureaTextPrimary)
    }
}

/**
 * Secondary payment-method button (Cash/QR/Member) -- outline style, unlike
 * the gold-gradient primary Charge Card CTA. Full-width and stacked, not a
 * side-by-side 2-up row: this column measures narrower on-device than its
 * 320dp cap (the sidebar + 340dp Bill Details panel eat most of the
 * landscape width at this app's real target resolution), and a 2-up row
 * left too little space per button -- "QR Code" wrapped/ellipsized even at
 * a shrunk font, visually confirmed on-device before switching to stacked.
 * fontSize=14sp/maxLines=1/ellipsis stay as a safety margin regardless.
 */
@Composable
private fun OureaPaymentMethodButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(OureaSurfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = OureaSkyBlue, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(text, color = OureaTextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun formatCents(cents: Int) = "$${"%.2f".format(cents / 100.0)}"

/**
 * Cash tendered entry -- digits typed on the keypad shift into a
 * calculator-style amount (typing "5","0","0" reads $5.00), mirroring how
 * card-terminal cash-drawer apps commonly take tendered amount. Confirm is
 * disabled until the tendered amount covers the total, so triggerCashPayment
 * never has to reject a negative change on its own.
 */
@Composable
fun OureaCashDialog(totalCents: Int, onConfirm: (cashReceivedCents: Int) -> Unit, onDismiss: () -> Unit) {
    var receivedCents by remember { mutableStateOf(0) }
    val change = receivedCents - totalCents

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, color = OureaSurface, tonalElevation = 8.dp) {
            // verticalScroll, not just wrap-content: the title+total+amount+change+4-row
            // keypad+buttons stack taller than this device's actual usable screen height at
            // landscape -- without scroll the bottom rows (incl. Confirm/Cancel) render past
            // the physical display edge and are simply unreachable. Visually confirmed
            // on-device before adding this.
            Column(
                modifier = Modifier.padding(24.dp).width(320.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Cash Payment", color = OureaTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Total Due: ${formatCents(totalCents)}", color = OureaTextSecondary)
                Spacer(modifier = Modifier.height(16.dp))
                Text(formatCents(receivedCents), color = OureaGold, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (change >= 0) "Change: ${formatCents(change)}" else "Amount short: ${formatCents(-change)}",
                    color = if (change >= 0) OureaTextSecondary else OureaRed
                )
                Spacer(modifier = Modifier.height(12.dp))
                NumericKeypad(
                    onNumberClick = { digit ->
                        // Check the post-multiplication value, not the pre-multiplication one --
                        // gating on the old value let a single keystroke jump from just under the
                        // $100,000 cap to ~10x over it (e.g. 9_999_999 -> 99_999_999) before the
                        // guard caught the next digit.
                        val next = receivedCents * 10 + digit.toInt()
                        if (next <= 100_000_00) receivedCents = next
                    },
                    onBackSpace = { receivedCents /= 10 },
                    onConfirm = { if (receivedCents >= totalCents) onConfirm(receivedCents) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(
                        onClick = { onConfirm(receivedCents) },
                        enabled = receivedCents >= totalCents,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = OureaGold, contentColor = Color(0xFF231A00))
                    ) { Text("Confirm") }
                }
            }
        }
    }
}

/** Matches VipRepository.resolveCardUidByQrCode's expected format (see feature/wash's member-QR-scan regex). */
private val MEMBER_CODE_REGEX = Regex("^[A-Za-z0-9]{6}$")

/**
 * Manual member-code entry (staff-typed, or a barcode scanner acting as a
 * keyboard) -- see MainActivity.triggerVipPayment's doc comment for why
 * this isn't an NFC tap. Submit stays disabled until the code matches the
 * 6-character format (shortened from 12, 2026-09-20) VipRepository actually
 * looks up, so an obviously malformed code never round-trips to the backend.
 */
@Composable
fun OureaMemberDialog(onSubmit: (code: String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    val isValid = MEMBER_CODE_REGEX.matches(code)

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, color = OureaSurface, tonalElevation = 8.dp) {
            Column(modifier = Modifier.padding(24.dp).width(340.dp).verticalScroll(rememberScrollState())) {
                Text("Member Card", color = OureaTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Enter or scan the 6-character member code", color = OureaTextSecondary, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { if (it.length <= 6) code = it.uppercase() },
                    singleLine = true,
                    placeholder = { Text("ABCD12", color = OureaTextSecondary) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = OureaSurfaceVariant,
                        unfocusedContainerColor = OureaSurfaceVariant,
                        focusedTextColor = OureaTextPrimary,
                        unfocusedTextColor = OureaTextPrimary,
                        focusedBorderColor = OureaSkyBlue,
                        unfocusedBorderColor = OureaSurfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(
                        onClick = { onSubmit(code) },
                        enabled = isValid,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = OureaGold, contentColor = Color(0xFF231A00))
                    ) { Text("Charge") }
                }
            }
        }
    }
}

/**
 * Waiting-for-scan QR dialog, driven by OureaQrUiState (Creating while the
 * session is being requested, Ready once QrUtils has a bitmap to show).
 * Resolution (paid/expired/error) is reported through the shared
 * OureaPaymentResultDialog like every other payment method, not here --
 * this dialog only ever shows the code and lets staff cancel out.
 */
@Composable
fun OureaQrPaymentDialog(state: OureaQrUiState, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        Surface(shape = MaterialTheme.shapes.medium, color = OureaSurface, tonalElevation = 8.dp) {
            Column(
                modifier = Modifier.padding(24.dp).width(320.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Scan to Pay", color = OureaTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                when (state) {
                    is OureaQrUiState.Ready -> {
                        Text(formatCents(state.totalCents), color = OureaGold, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Image(
                            bitmap = state.bitmap.asImageBitmap(),
                            contentDescription = "Payment QR code",
                            modifier = Modifier.size(240.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(8.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Waiting for customer to complete payment...", color = OureaTextSecondary, fontSize = 13.sp)
                    }
                    else -> {
                        CircularProgressIndicator(color = OureaGold)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Creating QR code...", color = OureaTextSecondary)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }
}

/**
 * Ourea's own dashboard, replacing feature/apex's InsightsScreen (a 2-line
 * revenue/count stub) for this app shell only -- app/iris still uses the
 * shared InsightsScreen unchanged, this is deliberately NOT a shared-module
 * edit. Stat cards + recent-orders list, styled after the reference
 * screenshots' dashboard layout at C:\goldsky\Requirment\Ourea\D1.jpg, but
 * in Ourea's own gold/sky-blue palette rather than the reference's flat
 * orange (see OureaTheme.kt's doc comment on why). Every number here comes
 * from TransactionRepository.getAllLocal() -- the same real local order
 * history TransactionHistoryScreen reads -- nothing is fabricated; a fresh
 * device with no orders yet shows zeros and an empty list, not sample data.
 */
@Composable
fun OureaDashboardScreen() {
    val context = LocalContext.current
    var orders by remember { mutableStateOf<List<OrderEntity>>(emptyList()) }

    LaunchedEffect(Unit) {
        orders = TransactionRepository.getAllLocal(context)
    }

    val todayStartMillis = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val todaysPaid = orders.filter { it.createdAt >= todayStartMillis && it.status == "PAID" }
    val revenueCents = todaysPaid.sumOf { it.amountCents }
    val orderCount = todaysPaid.size
    val avgOrderCents = if (orderCount > 0) revenueCents / orderCount else 0

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Dashboard", color = OureaTextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(java.util.Date()),
            color = OureaTextSecondary, fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OureaStatCard("Today's Revenue", formatCents(revenueCents), Icons.Default.AttachMoney, OureaGreen, Modifier.weight(1f))
            OureaStatCard("Today's Orders", orderCount.toString(), Icons.Default.ShoppingCart, OureaSkyBlue, Modifier.weight(1f))
            OureaStatCard("Avg. Order", formatCents(avgOrderCents), Icons.Default.TrendingUp, OureaGold, Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("Recent Orders", color = OureaTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))

        val recent = remember(orders) { orders.take(8) }
        if (recent.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(OureaSurface),
                contentAlignment = Alignment.Center
            ) {
                Text("No orders yet today", color = OureaTextSecondary)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(OureaSurface),
                contentPadding = PaddingValues(8.dp)
            ) {
                items(recent) { order -> OureaRecentOrderRow(order) }
            }
        }
    }
}

@Composable
private fun OureaStatCard(label: String, value: String, icon: ImageVector, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(16.dp)).background(OureaSurface).padding(20.dp)
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp))
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(value, color = OureaTextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, color = OureaTextSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun OureaRecentOrderRow(order: OrderEntity) {
    val (icon, methodLabel) = when (order.paymentMethod) {
        "CREDIT_CARD" -> Icons.Default.CreditCard to "Card"
        "CASH" -> Icons.Default.Payments to "Cash"
        "QR_CODE" -> Icons.Default.QrCode to "QR"
        "VIP_CARD" -> Icons.Default.Badge to "Member"
        else -> Icons.Default.Receipt to order.paymentMethod
    }
    val (statusColor, statusLabel) = when (order.status) {
        "PAID" -> OureaGreen to "Paid"
        "PENDING" -> OureaGold to "Pending"
        else -> OureaRed to order.status
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(OureaSurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = OureaSkyBlue, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(methodLabel, color = OureaTextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(
                SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date(order.createdAt)),
                color = OureaTextSecondary, fontSize = 12.sp
            )
        }
        Text(formatCents(order.amountCents), color = OureaTextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(statusColor.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(statusLabel, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * Lists orders parked via RetailViewModel.parkCurrentOrder (see the "挂单"
 * button in the Posly reference screenshots). Tapping Resume hands the
 * entity back to RetailViewModel.resumeOrder, which decodes the saved cart
 * back onto the current one -- see that function's doc comment for the
 * real bug fixed there while wiring this dialog up.
 */
@Composable
fun OureaHeldOrdersDialog(orders: List<ParkedOrderEntity>, onResume: (ParkedOrderEntity) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, color = OureaSurface, tonalElevation = 8.dp) {
            Column(modifier = Modifier.padding(24.dp).width(360.dp).heightIn(max = 500.dp)) {
                Text("Held Orders", color = OureaTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                if (orders.isEmpty()) {
                    Text("Nothing held right now", color = OureaTextSecondary)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(orders) { order ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(OureaSurfaceVariant)
                                    .clickable { onResume(order) }
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(order.tableName ?: "Quick Order", color = OureaTextPrimary, fontWeight = FontWeight.Medium)
                                    Text(
                                        SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date(order.createdAt)),
                                        color = OureaTextSecondary, fontSize = 12.sp
                                    )
                                }
                                Text(formatCents(order.subtotalCents), color = OureaGold, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
}
