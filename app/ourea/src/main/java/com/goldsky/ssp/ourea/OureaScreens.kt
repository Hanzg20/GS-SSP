package com.goldsky.ssp.ourea

import android.graphics.Bitmap
import com.goldsky.ssp.ourea.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.foundation.lazy.itemsIndexed
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
enum class OureaDestination { CHECKOUT, INSIGHTS, APEX, PRODUCTS, TRANSACTIONS, DAY_REPORT, SETTINGS }

/**
 * Left icon rail modeled on the reference screenshots' sidebar. Only wired
 * to destinations that already have a real screen behind them (Checkout,
 * Insights, Transactions, Settings) -- the reference also shows discount/
 * mail/notification icons with no corresponding feature in this codebase,
 * deliberately left out rather than built as dead UI.
 */
@Composable
fun OureaSidebar(current: OureaDestination, onSelect: (OureaDestination) -> Unit, onLock: () -> Unit, isManager: Boolean = true) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(64.dp)
            .background(OureaSidebar)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(CoreUiR.drawable.goldsky_logo),
            contentDescription = stringResource(R.string.ourea_goldsky),
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(32.dp))
        // Cashiers: checkout and their own transactions; managers also insights, products, Settings.
        if (isManager) {
            SidebarIcon(Icons.Default.Dashboard, current == OureaDestination.INSIGHTS) { onSelect(OureaDestination.INSIGHTS) }
            Spacer(modifier = Modifier.height(20.dp))
            // Apex (小海狸 / Beaver): the AI assistant.
            SidebarIcon(Icons.Default.AutoAwesome, current == OureaDestination.APEX) { onSelect(OureaDestination.APEX) }
            Spacer(modifier = Modifier.height(20.dp))
        }
        SidebarIcon(Icons.Default.ShoppingCart, current == OureaDestination.CHECKOUT) { onSelect(OureaDestination.CHECKOUT) }
        Spacer(modifier = Modifier.height(20.dp))
        if (isManager) {
            SidebarIcon(Icons.Default.Inventory2, current == OureaDestination.PRODUCTS) { onSelect(OureaDestination.PRODUCTS) }
            Spacer(modifier = Modifier.height(20.dp))
        }
        SidebarIcon(Icons.Default.History, current == OureaDestination.TRANSACTIONS || current == OureaDestination.DAY_REPORT) { onSelect(OureaDestination.TRANSACTIONS) }
        Spacer(modifier = Modifier.weight(1f))
        if (isManager) SidebarIcon(Icons.Default.Settings, current == OureaDestination.SETTINGS) { onSelect(OureaDestination.SETTINGS) }
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
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary)
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text(text, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
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

    // Full width for selling: the cart column scales with the screen (about 26%,
    // 260-340dp) and the categories are a chip row above the grid -- the fixed
    // 320dp cart plus a 130dp category column on the right left the product grid
    // squeezed between two dark side panels on narrower landscape screens.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val cartWidth = (maxWidth * 0.26f).coerceIn(260.dp, 340.dp)
    Row(modifier = Modifier.fillMaxSize()) {
        // Cart panel
        Column(
            modifier = Modifier
                .width(cartWidth)
                .fillMaxHeight()
                .background(OureaSurface)
                .padding(12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ourea_cart), color = OureaTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
                            Text(stringResource(R.string.ourea_held, parkedOrders.size), color = OureaTextPrimary, fontSize = 13.sp)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(cart, key = { _, item -> item.id }) { index, item ->
                    CartRow(
                        index + 1, item,
                        onIncrement = { viewModel.addItem(item.product, item.selectedModifiers) },
                        onDecrement = { viewModel.removeItem(item.id) }
                    )
                }
            }
            Divider(color = OureaSurfaceVariant)
            Spacer(modifier = Modifier.height(12.dp))
            // Subtotal / tax / total, the same figures the customer sees on Ourea Eye.
            val sale = viewModel.checkoutSale()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.ourea_subtotal), color = OureaTextSecondary, fontSize = 13.sp)
                Text("$${"%.2f".format(sale.subtotalCents / 100.0)}", color = OureaTextSecondary, fontSize = 13.sp)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.ourea_tax), color = OureaTextSecondary, fontSize = 13.sp)
                Text("$${"%.2f".format(sale.taxCents / 100.0)}", color = OureaTextSecondary, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.ourea_total), color = OureaTextSecondary)
                Text("$${"%.2f".format((sale.subtotalCents + sale.taxCents) / 100.0)}", color = OureaTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (cart.isNotEmpty()) {
                OutlinedButton(
                    onClick = { viewModel.parkCurrentOrder(context) },
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Icon(Icons.Default.Schedule, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.ourea_hold_order))
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            OureaGradientButton(
                text = stringResource(R.string.ourea_checkout),
                onClick = { if (total > 0) onCheckout(total) },
                enabled = total > 0,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            )
        }

        if (showHeldOrders) {
            OureaHeldOrdersDialog(
                orders = parkedOrders,
                summary = viewModel::parkedSummary,
                onResume = { entity ->
                    showHeldOrders = false
                    viewModel.resumeOrder(context, entity)
                },
                onDismiss = { showHeldOrders = false }
            )
        }

        // Product grid
        Column(modifier = Modifier.weight(1f).fillMaxHeight().background(OureaBackground).padding(horizontal = 12.dp, vertical = 10.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.ourea_search_products), color = OureaTextSecondary) },
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
            if (categories.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                OureaCategoryChips(categories = categories, selected = selectedCategory, onSelect = { selectedCategory = it })
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.ourea_no_products_yet_add_some), color = OureaTextSecondary)
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 140.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(filtered) { product -> ProductCard(product) { viewModel.addItem(product) } }
                }
            }
        }
    }
    }
}

/**
 * Category filter as one scrollable chip row above the grid (was a 130dp column
 * on the right). Only shown once a product has a category (Product.category
 * rides in attributes JSONB and is null until something sets it).
 */
@Composable
private fun OureaCategoryChips(categories: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { OureaCategoryChip(stringResource(R.string.ourea_all), selected == null) { onSelect(null) } }
        items(categories.size) { i -> OureaCategoryChip(categories[i], selected == categories[i]) { onSelect(categories[i]) } }
    }
}

@Composable
private fun OureaCategoryChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (isSelected) OureaGold else OureaSurface)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 14.dp)
    ) {
        Text(
            label,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else OureaTextPrimary,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 14.sp,
            maxLines = 1
        )
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
            Text(stringResource(R.string.ourea_products), color = OureaTextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ourea_products_count, catalog.size), color = OureaTextSecondary, fontSize = 13.sp)
                if (lowStockCount > 0) {
                    Text("  ·  ", color = OureaTextSecondary, fontSize = 13.sp)
                    Text(stringResource(R.string.ourea_low_stock_count, lowStockCount), color = OureaGold, fontSize = 13.sp)
                }
                if (outOfStockCount > 0) {
                    Text("  ·  ", color = OureaTextSecondary, fontSize = 13.sp)
                    Text(stringResource(R.string.ourea_out_of_stock_count, outOfStockCount), color = OureaRed, fontSize = 13.sp)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.ourea_search_products), color = OureaTextSecondary) },
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
                    Text(stringResource(R.string.ourea_no_products_yet_add_some), color = OureaTextSecondary)
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
        OureaCategoryRailItem(stringResource(R.string.ourea_all), selected == null) { onSelect(null) }
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
private fun CartRow(number: Int, item: CartItem, onIncrement: () -> Unit, onDecrement: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Line number, so the cashier and customer can point at "line 3".
        Text("$number", color = OureaTextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(24.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.product.name, color = OureaTextPrimary, fontWeight = FontWeight.Medium)
            Text("$${"%.2f".format(item.unitPriceCents / 100.0)} × ${item.quantity}", color = OureaTextSecondary, fontSize = 12.sp)
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
            // The whole card adds to the cart, not just the "+".
            .let { if (onAdd != null && !outOfStock) it.clickable { onAdd() } else it }
            .padding(12.dp)
            .let { if (outOfStock) it.alpha(0.5f) else it }
    ) {
        // No product photos in the catalog yet: no 72dp placeholder box either --
        // it only made every card taller, i.e. fewer products per screen.
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
        stockQty <= 0 -> stringResource(R.string.ourea_out_of_stock) to OureaRed
        stockQty <= 5 -> stringResource(R.string.ourea_low_left, stockQty) to OureaGold
        else -> stringResource(R.string.ourea_in_stock, stockQty) to OureaTextSecondary
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
            Text(stringResource(R.string.ourea_confirm_payment), color = OureaTextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(24.dp))
            OureaGradientButton(
                text = stringResource(R.string.ourea_charge_card),
                onClick = onChargeCard,
                icon = Icons.Default.CreditCard,
                modifier = Modifier.fillMaxWidth().height(72.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            OureaPaymentMethodButton(stringResource(R.string.ourea_cash), Icons.Default.Payments, onCash, Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(12.dp))
            OureaPaymentMethodButton(stringResource(R.string.ourea_qr_code), Icons.Default.QrCode, onQr, Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(12.dp))
            OureaPaymentMethodButton(stringResource(R.string.ourea_member_card), Icons.Default.Badge, onMember, Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ourea_back_to_order))
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
            Text(stringResource(R.string.ourea_bill_details), color = OureaTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            BillLine(stringResource(R.string.ourea_subtotal), subtotalCents, OureaTextSecondary)
            BillLine(stringResource(R.string.ourea_tax), taxCents, OureaTextSecondary)
            if (tipCents > 0) BillLine(stringResource(R.string.ourea_tip), tipCents, OureaTextSecondary)
            Divider(color = OureaSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.ourea_total_due), color = OureaTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
                Text(stringResource(R.string.ourea_cash_payment), color = OureaTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(stringResource(R.string.ourea_total_due_amount, formatCents(totalCents)), color = OureaTextSecondary)
                Spacer(modifier = Modifier.height(16.dp))
                Text(formatCents(receivedCents), color = OureaGold, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (change >= 0) stringResource(R.string.ourea_change, formatCents(change)) else stringResource(R.string.ourea_amount_short, formatCents(-change)),
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
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.ourea_cancel)) }
                    Button(
                        onClick = { onConfirm(receivedCents) },
                        enabled = receivedCents >= totalCents,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = OureaGold, contentColor = MaterialTheme.colorScheme.onPrimary)
                    ) { Text(stringResource(R.string.ourea_confirm)) }
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
                Text(stringResource(R.string.ourea_member_card), color = OureaTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(stringResource(R.string.ourea_enter_or_scan_the_6), color = OureaTextSecondary, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { if (it.length <= 6) code = it.uppercase() },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.ourea_abcd12), color = OureaTextSecondary) },
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
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.ourea_cancel)) }
                    Button(
                        onClick = { onSubmit(code) },
                        enabled = isValid,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = OureaGold, contentColor = MaterialTheme.colorScheme.onPrimary)
                    ) { Text(stringResource(R.string.ourea_charge)) }
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
                Text(stringResource(R.string.ourea_scan_to_pay), color = OureaTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                when (state) {
                    is OureaQrUiState.Ready -> {
                        Text(formatCents(state.totalCents), color = OureaGold, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Image(
                            bitmap = state.bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.ourea_payment_qr_code),
                            modifier = Modifier.size(240.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(8.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(stringResource(R.string.ourea_waiting_for_customer_to_complete), color = OureaTextSecondary, fontSize = 13.sp)
                    }
                    else -> {
                        CircularProgressIndicator(color = OureaGold)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(stringResource(R.string.ourea_creating_qr_code), color = OureaTextSecondary)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ourea_cancel)) }
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
        Text(stringResource(R.string.ourea_dashboard), color = OureaTextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            SimpleDateFormat("EEEE, MMMM d", androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(java.util.Date()),
            color = OureaTextSecondary, fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OureaStatCard(stringResource(R.string.ourea_todays_revenue), formatCents(revenueCents), Icons.Default.AttachMoney, OureaGreen, Modifier.weight(1f))
            OureaStatCard(stringResource(R.string.ourea_todays_orders), orderCount.toString(), Icons.Default.ShoppingCart, OureaSkyBlue, Modifier.weight(1f))
            OureaStatCard(stringResource(R.string.ourea_avg_order), formatCents(avgOrderCents), Icons.Default.TrendingUp, OureaGold, Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(stringResource(R.string.ourea_recent_orders), color = OureaTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))

        val recent = remember(orders) { orders.take(8) }
        if (recent.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(OureaSurface),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.ourea_no_orders_yet_today), color = OureaTextSecondary)
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
        "CREDIT_CARD" -> Icons.Default.CreditCard to stringResource(R.string.ourea_method_card)
        "CASH" -> Icons.Default.Payments to stringResource(R.string.ourea_method_cash)
        "QR_CODE" -> Icons.Default.QrCode to stringResource(R.string.ourea_method_qr)
        "VIP_CARD" -> Icons.Default.Badge to stringResource(R.string.ourea_method_member)
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
fun OureaHeldOrdersDialog(orders: List<ParkedOrderEntity>, summary: (ParkedOrderEntity) -> String, onResume: (ParkedOrderEntity) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, color = OureaSurface, tonalElevation = 8.dp) {
            Column(modifier = Modifier.padding(24.dp).width(360.dp).heightIn(max = 500.dp)) {
                Text(stringResource(R.string.ourea_held_orders), color = OureaTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                if (orders.isEmpty()) {
                    Text(stringResource(R.string.ourea_nothing_held_right_now), color = OureaTextSecondary)
                } else {
                    LazyColumn(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                Column(modifier = Modifier.weight(1f)) {
                                    // The items, so held orders can be told apart (all are "Quick Order").
                                    Text(
                                        summary(order).ifBlank { order.tableName ?: stringResource(R.string.ourea_quick_order) },
                                        color = OureaTextPrimary, fontWeight = FontWeight.Medium, maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Text(
                                        SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date(order.createdAt)),
                                        color = OureaTextSecondary, fontSize = 12.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(formatCents(order.subtotalCents), color = OureaGold, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ourea_close)) }
            }
        }
    }
}
