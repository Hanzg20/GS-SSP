// Arke (online ordering) words, on top of the page's shared ones (i18n.js T): same four
// languages, same pick (LANG). Arabic reads right to left -- the layout uses start/end.
import { LANG } from './i18n.js'

const zh = {
  edit: '修改', open: '营业中', closed: '休息中', pickupIn: (n) => `下单后约 ${n} 分钟可取`,
  todayHours: (r) => `今天 ${r}`, closedToday: '今天休息', hoursTitle: '营业时间', allDay: '全天',
  days: ['周一', '周二', '周三', '周四', '周五', '周六', '周日'], categories: '分类', storeInfo: '门店信息',
  shared: { noAllergenData: '过敏原未标注，请问店家', loading: '正在加载…' },
  noStore: '找不到这家店，请检查链接', pickup: '到店自取',
  openNow: (n) => `营业中 · 下单后约 ${n} 分钟可取`, closedNow: '现在不接线上订单', payOff: '这家店暂不支持线上付款',
  cart: '购物车', emptyCart: '购物车是空的', checkout: '去结账', clear: '清空', confirmClear: '确定清空？',
  yourInfo: '取餐人', name: '名字', phone: '手机号', phoneHint: '订单有问题时店家会打这个电话',
  orderNote: '给店家的备注', orderNotePh: '例如：少辣、多给餐具',
  fillInfo: '请填写名字和手机号', payAndOrder: (m) => `付款并下单 ${m}`, placing: '正在下单…',
  myOrder: '我的订单', myOrders: '我的订单', pickupCode: '取餐号', backToMenu: '返回菜单', orderAgain: '再点一单',
  st: {
    AWAITING_PAYMENT: '等待付款', NEW: '已付款，等店家接单', ACCEPTED: '店家正在准备', READY: '好了，可以来取了！',
    PICKED_UP: '已取餐，谢谢光临！', REJECTED: '店家无法接这单', EXPIRED: '订单已过期（没有付款）',
  },
  readyBy: (t) => `预计 ${t} 做好`, readyAt: (t) => `${t} 做好`,
  refund: { REFUNDING: '正在退款到你的卡', VOIDED: '已退回你的卡（未扣款）', REFUNDED: '已退款到你的卡，几天内到账' },
  refundPending: '店家会把钱退回你的卡', callStore: '打电话给店家', address: '取餐地址',
  payReview: '付款需要店家核对，请打电话给店家。', payPending: '还没收到付款确认。请不要重复付款，稍后刷新这个页面。',
  steps: ['已付款', '准备中', '可取餐'],
  errors: {
    store_closed: '店家现在不接线上订单', online_pay_off: '这家店暂不支持线上付款', bad_phone: '手机号不对，请检查',
    name_required: '请填写名字', too_many_orders: '下单太频繁了，请稍后再试', sold_out: '有菜刚刚售完：',
    item_unavailable: '有菜已下架，菜单已刷新', bad_option: '请重新选择口味：', items_required: '购物车是空的',
    order_expired: '订单超时了，请重新下单', order_new: '这单已经付过款了', too_many_attempts: '尝试太多次了，请稍后再试',
    bad_tip: '小费金额不对', bad_email: '邮箱格式不对', gateway_error: '付款服务暂时连不上，请稍后再试',
    offline: '连不上网络，请稍后重试', failed: '没有成功，请稍后再试',
  },
}

const en = {
  edit: 'Edit', open: 'Open', closed: 'Closed', pickupIn: (n) => `Ready about ${n} min after you order`,
  todayHours: (r) => `Today ${r}`, closedToday: 'Closed today', hoursTitle: 'Hours', allDay: 'Open all day',
  days: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'], categories: 'Categories', storeInfo: 'Store info',
  shared: { noAllergenData: 'Allergens not listed: ask the store', loading: 'Loading…' },
  noStore: "We can't find this store. Please check the link.", pickup: 'Pickup',
  openNow: (n) => `Open · ready about ${n} min after you order`, closedNow: 'Not taking online orders right now', payOff: "This store doesn't take online payment yet",
  cart: 'Your order', emptyCart: 'Your cart is empty', checkout: 'Checkout', clear: 'Clear', confirmClear: 'Clear all?',
  yourInfo: 'Pickup name', name: 'Name', phone: 'Phone', phoneHint: 'The store calls this number if there is a problem',
  orderNote: 'Note for the store', orderNotePh: 'e.g. less spicy, extra cutlery',
  fillInfo: 'Enter your name and phone', payAndOrder: (m) => `Pay and order ${m}`, placing: 'Placing your order…',
  myOrder: 'Your order', myOrders: 'My orders', pickupCode: 'Pickup no.', backToMenu: 'Back to the menu', orderAgain: 'Order again',
  st: {
    AWAITING_PAYMENT: 'Waiting for payment', NEW: 'Paid · waiting for the store', ACCEPTED: 'The store is preparing it', READY: 'Ready for pickup!',
    PICKED_UP: 'Picked up. Thank you!', REJECTED: "The store can't take this order", EXPIRED: 'Expired (not paid)',
  },
  readyBy: (t) => `Ready around ${t}`, readyAt: (t) => `Ready at ${t}`,
  refund: { REFUNDING: 'Refunding to your card', VOIDED: 'Cancelled on your card (not charged)', REFUNDED: 'Refunded to your card (a few days)' },
  refundPending: 'The store will refund your card', callStore: 'Call the store', address: 'Pickup address',
  payReview: 'The store needs to check this payment. Please call the store.', payPending: "We haven't had the payment confirmed yet. Please don't pay again; refresh this page in a moment.",
  steps: ['Paid', 'Preparing', 'Ready'],
  errors: {
    store_closed: 'The store is not taking online orders right now', online_pay_off: "This store doesn't take online payment yet", bad_phone: 'Please check the phone number',
    name_required: 'Please enter your name', too_many_orders: 'Too many orders. Please try again in a few minutes', sold_out: 'Just sold out: ',
    item_unavailable: 'An item is no longer available; the menu was refreshed', bad_option: 'Please choose the options again: ', items_required: 'Your cart is empty',
    order_expired: 'This order timed out. Please order again', order_new: 'This order is already paid', too_many_attempts: 'Too many tries. Please wait a moment',
    bad_tip: 'That tip amount is not valid', bad_email: 'Please check the email', gateway_error: "The payment service can't be reached. Please try again",
    offline: 'No connection. Please try again', failed: "That didn't work. Please try again",
  },
}

const fr = {
  edit: 'Modifier', open: 'Ouvert', closed: 'Fermé', pickupIn: (n) => `Prêt environ ${n} min après la commande`,
  todayHours: (r) => `Aujourd’hui ${r}`, closedToday: 'Fermé aujourd’hui', hoursTitle: 'Heures d’ouverture', allDay: 'Ouvert toute la journée',
  days: ['Lun', 'Mar', 'Mer', 'Jeu', 'Ven', 'Sam', 'Dim'], categories: 'Catégories', storeInfo: 'Infos du commerce',
  shared: { noAllergenData: 'Allergènes non indiqués : demandez au commerce', loading: 'Chargement…' },
  noStore: 'Ce commerce est introuvable. Vérifiez le lien.', pickup: 'À emporter',
  openNow: (n) => `Ouvert · prêt environ ${n} min après la commande`, closedNow: 'Pas de commandes en ligne pour le moment', payOff: "Ce commerce n'accepte pas encore le paiement en ligne",
  cart: 'Votre commande', emptyCart: 'Votre panier est vide', checkout: 'Passer à la caisse', clear: 'Vider', confirmClear: 'Tout vider ?',
  yourInfo: 'Nom pour le retrait', name: 'Nom', phone: 'Téléphone', phoneHint: 'Le commerce appelle ce numéro en cas de problème',
  orderNote: 'Note pour le commerce', orderNotePh: 'ex. moins épicé, ustensiles',
  fillInfo: 'Entrez votre nom et votre téléphone', payAndOrder: (m) => `Payer et commander ${m}`, placing: 'Envoi de la commande…',
  myOrder: 'Votre commande', myOrders: 'Mes commandes', pickupCode: 'N° de retrait', backToMenu: 'Retour au menu', orderAgain: 'Commander encore',
  st: {
    AWAITING_PAYMENT: 'En attente de paiement', NEW: 'Payée · en attente du commerce', ACCEPTED: 'En préparation', READY: 'Prête à récupérer !',
    PICKED_UP: 'Récupérée. Merci !', REJECTED: 'Le commerce ne peut pas prendre cette commande', EXPIRED: 'Expirée (non payée)',
  },
  readyBy: (t) => `Prête vers ${t}`, readyAt: (t) => `Prête à ${t}`,
  refund: { REFUNDING: 'Remboursement en cours', VOIDED: 'Annulée sur votre carte (non débitée)', REFUNDED: 'Remboursée sur votre carte (quelques jours)' },
  refundPending: 'Le commerce remboursera votre carte', callStore: 'Appeler le commerce', address: 'Adresse de retrait',
  payReview: 'Le commerce doit vérifier ce paiement. Veuillez l’appeler.', payPending: 'Paiement pas encore confirmé. Ne payez pas une deuxième fois ; actualisez la page dans un instant.',
  steps: ['Payée', 'En préparation', 'Prête'],
  errors: {
    store_closed: 'Le commerce ne prend pas de commandes en ligne pour le moment', online_pay_off: "Ce commerce n'accepte pas encore le paiement en ligne", bad_phone: 'Vérifiez le numéro de téléphone',
    name_required: 'Entrez votre nom', too_many_orders: 'Trop de commandes. Réessayez dans quelques minutes', sold_out: 'Rupture de stock : ',
    item_unavailable: "Un article n'est plus disponible ; menu actualisé", bad_option: 'Choisissez à nouveau les options : ', items_required: 'Votre panier est vide',
    order_expired: 'Commande expirée. Recommencez', order_new: 'Cette commande est déjà payée', too_many_attempts: 'Trop d’essais. Patientez un instant',
    bad_tip: 'Pourboire invalide', bad_email: 'Vérifiez le courriel', gateway_error: 'Service de paiement injoignable. Réessayez',
    offline: 'Pas de connexion. Réessayez', failed: 'Échec. Réessayez',
  },
}

const ar = {
  edit: 'تعديل', open: 'مفتوح', closed: 'مغلق', pickupIn: (n) => `جاهز بعد نحو ${n} دقيقة من الطلب`,
  todayHours: (r) => `اليوم ${r}`, closedToday: 'مغلق اليوم', hoursTitle: 'ساعات العمل', allDay: 'مفتوح طوال اليوم',
  days: ['الاثنين', 'الثلاثاء', 'الأربعاء', 'الخميس', 'الجمعة', 'السبت', 'الأحد'], categories: 'الفئات', storeInfo: 'معلومات المتجر',
  shared: { noAllergenData: 'مسببات الحساسية غير مذكورة: اسأل المتجر', loading: 'جارٍ التحميل…' },
  noStore: 'لم نجد هذا المتجر. تحقّق من الرابط.', pickup: 'استلام من المتجر',
  openNow: (n) => `مفتوح · جاهز بعد نحو ${n} دقيقة من الطلب`, closedNow: 'لا يستقبل طلبات عبر الإنترنت الآن', payOff: 'هذا المتجر لا يقبل الدفع عبر الإنترنت بعد',
  cart: 'طلبك', emptyCart: 'السلة فارغة', checkout: 'إتمام الطلب', clear: 'إفراغ', confirmClear: 'إفراغ الكل؟',
  yourInfo: 'اسم المستلم', name: 'الاسم', phone: 'الهاتف', phoneHint: 'يتصل المتجر بهذا الرقم عند وجود مشكلة',
  orderNote: 'ملاحظة للمتجر', orderNotePh: 'مثلاً: أقل حرارة، أدوات طعام',
  fillInfo: 'أدخل اسمك ورقم هاتفك', payAndOrder: (m) => `ادفع واطلب ${m}`, placing: 'جارٍ إرسال الطلب…',
  myOrder: 'طلبك', myOrders: 'طلباتي', pickupCode: 'رقم الاستلام', backToMenu: 'العودة إلى القائمة', orderAgain: 'اطلب مجددًا',
  st: {
    AWAITING_PAYMENT: 'بانتظار الدفع', NEW: 'مدفوع · بانتظار المتجر', ACCEPTED: 'المتجر يحضّر طلبك', READY: 'جاهز للاستلام!',
    PICKED_UP: 'تم الاستلام. شكرًا!', REJECTED: 'لا يستطيع المتجر قبول هذا الطلب', EXPIRED: 'انتهت صلاحيته (لم يُدفع)',
  },
  readyBy: (t) => `جاهز نحو ${t}`, readyAt: (t) => `جاهز الساعة ${t}`,
  refund: { REFUNDING: 'جارٍ الاسترداد إلى بطاقتك', VOIDED: 'أُلغي على بطاقتك (لم يُخصم)', REFUNDED: 'استُرد إلى بطاقتك (خلال أيام)' },
  refundPending: 'سيعيد المتجر المبلغ إلى بطاقتك', callStore: 'اتصل بالمتجر', address: 'عنوان الاستلام',
  payReview: 'يحتاج المتجر إلى مراجعة هذا الدفع. يرجى الاتصال بالمتجر.', payPending: 'لم يتأكد الدفع بعد. لا تدفع مرة أخرى؛ حدّث الصفحة بعد قليل.',
  steps: ['مدفوع', 'قيد التحضير', 'جاهز'],
  errors: {
    store_closed: 'المتجر لا يستقبل طلبات عبر الإنترنت الآن', online_pay_off: 'هذا المتجر لا يقبل الدفع عبر الإنترنت بعد', bad_phone: 'تحقّق من رقم الهاتف',
    name_required: 'أدخل اسمك', too_many_orders: 'طلبات كثيرة. حاول بعد دقائق', sold_out: 'نفد للتو: ',
    item_unavailable: 'صنف لم يعد متاحًا؛ تم تحديث القائمة', bad_option: 'اختر الخيارات مجددًا: ', items_required: 'السلة فارغة',
    order_expired: 'انتهت مهلة الطلب. اطلب مجددًا', order_new: 'هذا الطلب مدفوع بالفعل', too_many_attempts: 'محاولات كثيرة. انتظر قليلاً',
    bad_tip: 'مبلغ الإكرامية غير صالح', bad_email: 'تحقّق من البريد الإلكتروني', gateway_error: 'تعذّر الوصول إلى خدمة الدفع. حاول مجددًا',
    offline: 'لا يوجد اتصال. حاول مجددًا', failed: 'لم ينجح ذلك. حاول مجددًا',
  },
}

export const AK = { zh, en, fr, ar }[LANG] || en
