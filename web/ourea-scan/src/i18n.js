// The page's words in each language the customer can pick (Ourea Scan). Arabic reads right to left.
// Dish names come from the store (and Cael's translations, zh / en); Beaver explains a dish in any of these.

export const LANG_KEY = 'ourea_scan_lang'
export const LANGS = [['zh', '中文'], ['en', 'English'], ['fr', 'Français'], ['ar', 'العربية']]
const known = LANGS.map(([l]) => l)
// The customer's own pick (door / settings) wins, else the phone's language, else English.
const picked = (() => { try { return localStorage.getItem(LANG_KEY) } catch { return null } })()
const phone = (navigator.language || '').toLowerCase().slice(0, 2)
export const LANG = known.includes(picked) ? picked : known.includes(phone) ? phone : 'en'
export const zh = LANG === 'zh'
export const RTL = LANG === 'ar'
// The whole page follows: Arabic lays out right to left (Tailwind start/end classes mirror with it).
document.documentElement.lang = LANG
document.documentElement.dir = RTL ? 'rtl' : 'ltr'

/** Switch language: remembered on this phone, the page reloads in it. */
export function pickLang(l) {
  if (l === LANG) return
  try { localStorage.setItem(LANG_KEY, l) } catch { /* private mode: fine */ }
  window.location.reload()
}

const zhT = {
  scanFirst: '请扫描餐桌上的二维码点餐', badCode: '这个二维码已失效，请找服务员', loading: '正在加载菜单…', offline: '连不上网络，请稍后重试',
  table: '桌', all: '全部', soldOut: '已售完', add: '加入', required: '必选', pickOne: '单选', upTo: (n) => `最多选 ${n} 项`, optional: '可选',
  order: '我的点单', items: (n) => `${n} 件`, subtotal: '小计', tax: '税（估算）', total: '合计', note: '给厨房的备注', notePh: '例如：少辣、走葱',
  itemNote: '这道菜的备注', send: '下单', sending: '提交中…', save: '保存', cancel: '取消',
  confirmHint: '下单后送到厨房。吃完点「买单」看账单。', myOrders: '已提交的点单',
  pending: '等待店员确认', toKitchen: '正在送厨房', accepted: '已送厨房', rejected: '未被接受', orderMore: '继续点菜',
  errors: { too_many_orders: '提交太频繁了，请等店员处理后再点', sold_out: '有菜刚刚售完：', item_unavailable: '有菜已下架，请刷新菜单', bad_option: '请重新选择口味：', unknown_table: '这个二维码已失效，请找服务员' },
  failed: '没有提交成功', retry: '重试',
  contains: '含', noAllergenData: '过敏原未标注，请问服务员',
  guide: '小海狸', guideTitle: '小海狸 · AI 点餐向导', guideHello: '你好！我是小海狸 🦫 用任何语言问我：菜是什么、辣不辣、有没有过敏原、几个人点多少。',
  guideHint: '例如：我对花生过敏，哪些能吃？', ask: '发送', listening: '正在听…点一下停止', addSuggestion: '加入',
  guideErrors: { busy: '小海狸有点忙，请过半分钟再试', table_limit: '问得有点多，请稍后再试', store_limit: '今天的提问已达上限，请问服务员', no_model: 'AI 向导暂时不可用，请问服务员', offline: '连不上网络，请稍后再试', failed: '没听明白，请换个说法再试', no_speech_service: '语音暂不可用，请打字', too_long: '录音太长了' },
  aiNote: 'AI 回答仅供参考，过敏请务必告诉服务员。',
  voiceTitle: '跟小海狸说', voiceEntry: '跟小海狸说', voiceEntrySub: '想吃什么、想问什么，说一句就行',
  voiceHint: '点一下话筒，点菜、提问都可以', voiceExample: '例如：两碗牛肉面，一碗不要葱 · 哪些菜不辣？',
  voiceHearing: '正在听清你说的…', voiceThinking: '小海狸正在对照菜单…', voiceNothing: '没听到声音，请再说一次',
  voiceNoLines: '菜单上没找到对应的菜，换个说法试试？', youSaid: '你说', fromMenu: '来自本店菜单 · 小海狸',
  whichOne: (h) => `「${h}」想要哪一道？`, notOnMenu: '菜单上没有：', pickOptions: '选口味',
  locale: 'zh-CN', partyTitle: '几位用餐？', partySpice: '能吃辣吗？', spice: { none: '不吃辣', mild: '微辣可以', any: '都可以' }, partyHint: '选填：小海狸会按你们的人数和口味配菜',
  peopleN: (n) => `${n} 位`, picksTitle: (n) => `小海狸为你们 ${n} 位配的`, picksBtn: '让小海狸为这桌配菜', picksGo: '配菜', picksLoading: '小海狸正在配菜…',
  picksAgain: '换一换', picksAll: (m) => `全部加入 · ${m}`, partyDone: '好了',
  enoughBtn: '小海狸看看够不够', enoughNeedSize: '几位用餐？小海狸好帮你们看', enoughThinking: '小海狸在看…', enoughAgain: '再看看',
  verdict: { enough: '够了', more: '还差一点', less: '有点多' },
  addAll: (n, m) => `全部加入购物车 · ${n} 份 ${m}`, addedN: (n) => `已加入 ${n} 份`, sayMore: '再说', typeInstead: '打字', tapToStop: '说完点一下停止',
  bill: '买单', billTitle: '账单', noBill: '这桌还没有账单', taxActual: '税', alreadyPaid: '已付', balance: '待付',
  payServer: '请找服务员买单', allPaid: '这桌已经付清了，谢谢！', howMuch: '付多少', payAll: '全部', split: 'AA 平分', customAmount: '自定金额',
  perPerson: (m) => `每人 ${m}`, tipTitle: '小费', tipBasis: (m) => `按税前 ${m} 计算`, customTip: '自定', noTip: '不加小费',
  chooseTip: '请先选择小费', payButton: (m) => `付款 ${m}`, payNow: '付款', confirming: '正在确认付款…',
  paidOk: (m) => `已付款 ${m}，谢谢！`, stillOwed: (m) => `这桌还差 ${m}`, payMore: '再付一份',
  payFailed: '付款没有成功，没有扣款。', payReview: '付款需要店员核对，请把这个页面给服务员看。',
  payPending: '还没收到付款确认。请不要重复付款，找服务员确认一下。', nuveiLocale: 'zh',
  emailLabel: '收据邮箱', emailHint: '只交给支付公司 Nuvei 用于这笔付款，本店不保存。', enterEmail: '请填写邮箱',
  billErrors: { bad_amount: '金额超过了待付金额，账单已刷新', being_paid: '同桌有人正在付款，请稍后再看', no_open_check: '这桌的账单已经结了',
    online_pay_off: '这家店暂不支持手机付款，请找服务员', too_many_attempts: '尝试太多次了，请找服务员', bad_tip: '小费金额不对',
    gateway_error: '付款服务暂时连不上，请稍后再试', offline: '连不上网络，请稍后重试', failed: '没有成功，请稍后再试', bad_email: '邮箱格式不对' },
  search: '搜索菜品', noMatch: '没有找到这道菜', ordered: (n) => `已点 ×${n}`, from: '起', soldOutShort: '售完',
  avoid: '我的忌口', avoidTitle: '选择你不吃的', avoidHint: '含这些的菜会标红；没标注过敏原的菜会提示「未标注」。', avoidDone: '好了', avoidClear: '清除',
  hasAvoid: (a) => `含${a}`, unknownAvoid: '过敏原未标注',
  stepSent: '已提交', stepConfirm: '店员确认', stepKitchen: '已送厨房', orderNo: (n) => `第 ${n} 单`,
  call: '呼叫', callTitle: '需要什么？', callWater: '加水', callBill: '要买单', callHelp: '叫服务员',
  callSent: '已通知服务员，马上过来', callAck: '服务员已经在路上', callCancel: '取消呼叫', callAgain: '再叫一次',
  callErrors: { too_many_calls: '叫得有点多，服务员马上就来', offline: '连不上网络，请举手示意服务员', failed: '没有叫到，请举手示意服务员' },
  poweredBy: '技术支持', lang: 'EN', details: '详情', close: '关闭', ingredients: '配料',
  yourTable: '你的桌号是', pickLang: '选择语言', yourName: '你的名字（选填）', nameHint: '同桌的人会看到是谁点的菜', start: '开始点餐',
  tableOrders: '这桌的点单', checkNo: (n) => `账单 #${n}`, nothingYet: '这桌还没有点菜', inKitchen: '已下厨', roundN: (n) => `第 ${n} 轮`,
  orderMoreBtn: '继续加菜', payBtn: '买单', sentBy: (n) => `${n} 提交`, me: '我', guest: '客人',
  tableCart: '这桌的购物车', clearCart: '清空', confirmClear: '确定清空？', cartChanged: '同桌刚改了购物车，请再看一眼再提交',
  cartErrors: { sold_out: '刚刚售完：', item_unavailable: '这道菜已下架', bad_option: '请重新选择口味：', cart_full: '购物车满了，先提交一部分吧', offline: '连不上网络，请稍后重试', failed: '没有加上，请再试一次' },
  autoKitchen: '已直接送到厨房', allCategories: '全部分类', menuTitle: '设置', language: '语言', changeName: '我的名字', saveName: '保存',
  explain: '小海狸，这道菜是什么？', explaining: '小海狸正在讲…',
}

const en = {
  scanFirst: 'Scan the QR code on your table to order', badCode: 'This QR code is no longer valid. Please ask a server.', loading: 'Loading the menu…', offline: 'No connection. Please try again.',
  table: 'Table', all: 'All', soldOut: 'Sold out', add: 'Add', required: 'Required', pickOne: 'Pick one', upTo: (n) => `Up to ${n}`, optional: 'Optional',
  order: 'Your order', items: (n) => `${n} items`, subtotal: 'Subtotal', tax: 'Tax (est.)', total: 'Total', note: 'Note for the kitchen', notePh: 'e.g. less spicy, no green onion',
  itemNote: 'Note for this dish', send: 'Place order', sending: 'Sending…', save: 'Save', cancel: 'Cancel',
  confirmHint: 'Your order goes to the kitchen. Tap Bill to see and pay your bill.', myOrders: 'Your orders',
  pending: 'Waiting for a server to confirm', toKitchen: 'Going to the kitchen', accepted: 'Sent to the kitchen', rejected: 'Not accepted', orderMore: 'Order more',
  errors: { too_many_orders: 'Too many orders at once. Please wait for a server.', sold_out: 'Just sold out: ', item_unavailable: 'An item is no longer available. Please reload the menu.', bad_option: 'Please choose the options again: ', unknown_table: 'This QR code is no longer valid. Please ask a server.' },
  failed: 'Your order was not sent', retry: 'Try again',
  contains: 'Contains', noAllergenData: 'Allergens not listed: ask your server',
  guide: 'Beaver', guideTitle: 'Beaver · AI menu guide', guideHello: "Hi! I'm Beaver 🦫 Ask me in any language: what a dish is, how spicy, allergens, how much to order for your group.",
  guideHint: "e.g. I'm allergic to peanuts, what can I eat?", ask: 'Send', listening: 'Listening… tap to stop', addSuggestion: 'Add',
  guideErrors: { busy: 'Beaver is busy, try again in half a minute', table_limit: 'Lots of questions! Please wait a moment', store_limit: "Today's questions are used up, please ask your server", no_model: 'The AI guide is unavailable, please ask your server', offline: 'No connection, try again', failed: "Sorry, I didn't get that, try rephrasing", no_speech_service: 'Voice is unavailable, please type', too_long: 'That recording is too long' },
  aiNote: 'AI answers are a guide only. Always tell your server about allergies.',
  voiceTitle: 'Talk to Beaver', voiceEntry: 'Talk to Beaver', voiceEntrySub: 'Order or ask anything, in one sentence',
  voiceHint: 'Tap the mic: order or ask', voiceExample: "e.g. Two beef noodles, one without onion · What isn't spicy?",
  voiceHearing: 'Getting your words…', voiceThinking: 'Beaver is checking the menu…', voiceNothing: "Didn't hear anything, try again",
  voiceNoLines: "Couldn't match that to the menu. Try saying it another way?", youSaid: 'You said', fromMenu: "From this store's menu · Beaver",
  whichOne: (h) => `Which "${h}"?`, notOnMenu: 'Not on the menu:', pickOptions: 'Choose options',
  locale: navigator.language && !navigator.language.toLowerCase().startsWith('zh') ? navigator.language : 'en', partyTitle: 'How many of you?', partySpice: 'Spice?', spice: { none: 'No spice', mild: 'Mild is OK', any: 'Anything' }, partyHint: 'Optional: Beaver sizes its picks to your table',
  peopleN: (n) => `${n} ${n === 1 ? 'person' : 'people'}`, picksTitle: (n) => `Beaver's picks for ${n}`, picksBtn: 'Let Beaver set up your table', picksGo: 'Go', picksLoading: 'Beaver is picking…',
  picksAgain: 'Others', picksAll: (m) => `Add all · ${m}`, partyDone: 'Done',
  enoughBtn: 'Ask Beaver: is this enough?', enoughNeedSize: 'How many of you?', enoughThinking: 'Beaver is looking…', enoughAgain: 'Look again',
  verdict: { enough: 'Enough', more: 'A bit short', less: 'Too much' },
  addAll: (n, m) => `Add all to cart · ${n} for ${m}`, addedN: (n) => `${n} added`, sayMore: 'Say more', typeInstead: 'Type', tapToStop: 'Tap when you are done',
  bill: 'Bill', billTitle: 'Bill', noBill: 'No bill at this table yet', taxActual: 'Tax', alreadyPaid: 'Paid', balance: 'Balance due',
  payServer: 'Please pay your server', allPaid: 'This table is all paid. Thank you!', howMuch: 'How much', payAll: 'All of it', split: 'Split evenly', customAmount: 'Other amount',
  perPerson: (m) => `${m} each`, tipTitle: 'Tip', tipBasis: (m) => `Of ${m} before tax`, customTip: 'Custom', noTip: 'No tip',
  chooseTip: 'Choose a tip first', payButton: (m) => `Pay ${m}`, payNow: 'Pay', confirming: 'Confirming your payment…',
  paidOk: (m) => `Paid ${m}. Thank you!`, stillOwed: (m) => `${m} still owed at this table`, payMore: 'Pay another share',
  payFailed: 'The payment did not go through. You were not charged.', payReview: 'A server needs to check this payment. Please show them this screen.',
  payPending: "We haven't had the confirmation yet. Please don't pay again: ask your server.", nuveiLocale: 'en',
  emailLabel: 'Email for your receipt', emailHint: 'Goes only to Nuvei, the payment company, for this payment. The store does not keep it.', enterEmail: 'Enter your email',
  billErrors: { bad_amount: 'That is more than the balance. The bill was refreshed.', being_paid: 'Someone at your table is paying right now. Try again in a moment.', no_open_check: 'This bill is already closed.',
    online_pay_off: "This store doesn't take payment on the phone. Please pay your server.", too_many_attempts: 'Too many tries. Please pay your server.', bad_tip: 'That tip amount is not allowed.',
    gateway_error: "Payment isn't available right now. Try again in a moment.", offline: 'No connection. Please try again.', failed: 'That did not work. Please try again.', bad_email: 'That email address does not look right.' },
  search: 'Search the menu', noMatch: 'No dish matches', ordered: (n) => `Ordered ×${n}`, from: '', soldOutShort: 'Sold out',
  avoid: 'My allergies', avoidTitle: "What you don't eat", avoidHint: "Dishes with these get a red label; dishes without allergen info say so.", avoidDone: 'Done', avoidClear: 'Clear',
  hasAvoid: (a) => `Contains ${a}`, unknownAvoid: 'Allergens not listed',
  stepSent: 'Sent', stepConfirm: 'Server confirms', stepKitchen: 'In the kitchen', orderNo: (n) => `Order ${n}`,
  call: 'Call', callTitle: 'What do you need?', callWater: 'Water', callBill: 'Ready to pay', callHelp: 'A server',
  callSent: 'A server has been told and is coming', callAck: 'A server is on the way', callCancel: 'Cancel the call', callAgain: 'Call again',
  callErrors: { too_many_calls: 'Lots of calls! A server is coming', offline: 'No connection. Please wave to a server.', failed: 'The call did not go through. Please wave to a server.' },
  poweredBy: 'Powered by', lang: '中文', details: 'Details', close: 'Close', ingredients: 'Ingredients',
  yourTable: 'Your table is', pickLang: 'Language', yourName: 'Your name (optional)', nameHint: 'So your table can see who ordered what', start: 'Start ordering',
  tableOrders: "Your table's order", checkNo: (n) => `Check #${n}`, nothingYet: 'Nothing ordered at this table yet', inKitchen: 'In the kitchen', roundN: (n) => `Round ${n}`,
  orderMoreBtn: 'Order more', payBtn: 'Bill', sentBy: (n) => `Sent by ${n}`, me: 'Me', guest: 'Guest',
  tableCart: "Your table's cart", clearCart: 'Clear', confirmClear: 'Clear it all?', cartChanged: 'Someone at your table just changed the cart. Please check it, then send.',
  cartErrors: { sold_out: 'Just sold out: ', item_unavailable: 'That dish is no longer available', bad_option: 'Please choose the options again: ', cart_full: 'The cart is full. Send some first.', offline: 'No connection. Please try again.', failed: "That didn't go in. Please try again." },
  autoKitchen: 'Sent straight to the kitchen', allCategories: 'All categories', menuTitle: 'Settings', language: 'Language', changeName: 'My name', saveName: 'Save',
  explain: 'Beaver, what is this dish?', explaining: 'Beaver is explaining…',
}

const fr = {
  scanFirst: 'Scannez le code QR de votre table pour commander', badCode: "Ce code QR n'est plus valide. Demandez à un serveur.", loading: 'Chargement du menu…', offline: 'Pas de connexion. Réessayez.',
  table: 'Table', all: 'Tout', soldOut: 'Épuisé', add: 'Ajouter', required: 'Obligatoire', pickOne: 'Un choix', upTo: (n) => `Jusqu'à ${n}`, optional: 'Facultatif',
  order: 'Votre commande', items: (n) => `${n} articles`, subtotal: 'Sous-total', tax: 'Taxes (est.)', total: 'Total', note: 'Note pour la cuisine', notePh: 'ex. moins épicé, sans oignon vert',
  itemNote: 'Note pour ce plat', send: 'Commander', sending: 'Envoi…', save: 'Enregistrer', cancel: 'Annuler',
  confirmHint: 'Votre commande part en cuisine. Touchez Addition pour voir et payer.', myOrders: 'Vos commandes',
  pending: "En attente d'un serveur", toKitchen: 'En route vers la cuisine', accepted: 'Envoyée en cuisine', rejected: 'Non acceptée', orderMore: 'Commander encore',
  errors: { too_many_orders: 'Trop de commandes à la fois. Attendez un serveur.', sold_out: 'Vient de s’épuiser : ', item_unavailable: "Un plat n'est plus disponible. Rechargez le menu.", bad_option: 'Choisissez de nouveau les options : ', unknown_table: "Ce code QR n'est plus valide. Demandez à un serveur." },
  failed: "Votre commande n'a pas été envoyée", retry: 'Réessayer',
  contains: 'Contient', noAllergenData: 'Allergènes non indiqués : demandez au serveur',
  guide: 'Castor', guideTitle: 'Castor · guide du menu IA', guideHello: 'Bonjour ! Je suis Castor 🦫 Posez vos questions dans votre langue : ce qu’est un plat, s’il est épicé, les allergènes, combien commander.',
  guideHint: 'ex. Je suis allergique aux arachides, que puis-je manger ?', ask: 'Envoyer', listening: 'J’écoute… touchez pour arrêter', addSuggestion: 'Ajouter',
  guideErrors: { busy: 'Castor est occupé, réessayez dans 30 secondes', table_limit: 'Beaucoup de questions ! Patientez un instant', store_limit: "Les questions du jour sont épuisées, demandez au serveur", no_model: "Le guide IA n'est pas disponible, demandez au serveur", offline: 'Pas de connexion, réessayez', failed: 'Je n’ai pas compris, reformulez', no_speech_service: 'La voix est indisponible, écrivez', too_long: 'Enregistrement trop long' },
  aiNote: 'Les réponses IA sont indicatives. Signalez toujours vos allergies au serveur.',
  voiceTitle: 'Parler à Castor', voiceEntry: 'Parler à Castor', voiceEntrySub: 'Commandez ou posez une question, en une phrase',
  voiceHint: 'Touchez le micro : commandez ou demandez', voiceExample: 'ex. Deux nouilles au bœuf, une sans oignon · Qu’est-ce qui n’est pas épicé ?',
  voiceHearing: 'Je transcris…', voiceThinking: 'Castor vérifie le menu…', voiceNothing: "Je n'ai rien entendu, réessayez",
  voiceNoLines: 'Rien trouvé au menu. Essayez autrement ?', youSaid: 'Vous avez dit', fromMenu: 'Du menu de ce restaurant · Castor',
  whichOne: (h) => `Quel « ${h} » ?`, notOnMenu: 'Pas au menu :', pickOptions: 'Choisir les options',
  locale: 'fr-CA', partyTitle: 'Combien êtes-vous ?', partySpice: 'Épicé ?', spice: { none: 'Pas épicé', mild: 'Doux, ça va', any: 'Tout' }, partyHint: 'Facultatif : Castor adapte ses choix à votre table',
  peopleN: (n) => `${n} ${n === 1 ? 'personne' : 'personnes'}`, picksTitle: (n) => `Les choix de Castor pour ${n}`, picksBtn: 'Laissez Castor composer votre table', picksGo: 'Go', picksLoading: 'Castor choisit…',
  picksAgain: 'Autres', picksAll: (m) => `Tout ajouter · ${m}`, partyDone: 'OK',
  enoughBtn: 'Castor, est-ce assez ?', enoughNeedSize: 'Combien êtes-vous ?', enoughThinking: 'Castor regarde…', enoughAgain: 'Revoir',
  verdict: { enough: 'Assez', more: 'Un peu juste', less: 'Trop' },
  addAll: (n, m) => `Tout ajouter · ${n} pour ${m}`, addedN: (n) => `${n} ajouté(s)`, sayMore: 'Encore', typeInstead: 'Écrire', tapToStop: 'Touchez quand vous avez fini',
  bill: 'Addition', billTitle: 'Addition', noBill: "Pas encore d'addition à cette table", taxActual: 'Taxes', alreadyPaid: 'Payé', balance: 'Solde dû',
  payServer: 'Veuillez payer votre serveur', allPaid: 'Cette table est réglée. Merci !', howMuch: 'Combien', payAll: 'Tout', split: 'Partager également', customAmount: 'Autre montant',
  perPerson: (m) => `${m} chacun`, tipTitle: 'Pourboire', tipBasis: (m) => `Sur ${m} avant taxes`, customTip: 'Autre', noTip: 'Sans pourboire',
  chooseTip: "Choisissez d'abord un pourboire", payButton: (m) => `Payer ${m}`, payNow: 'Payer', confirming: 'Confirmation du paiement…',
  paidOk: (m) => `${m} payé. Merci !`, stillOwed: (m) => `Il reste ${m} à cette table`, payMore: 'Payer une autre part',
  payFailed: "Le paiement n'a pas abouti. Rien n'a été débité.", payReview: 'Un serveur doit vérifier ce paiement. Montrez-lui cet écran.',
  payPending: "Pas encore de confirmation. Ne payez pas une deuxième fois : demandez au serveur.", nuveiLocale: 'fr',
  emailLabel: 'Courriel pour le reçu', emailHint: "Envoyé seulement à Nuvei, la société de paiement, pour ce paiement. Le restaurant ne le garde pas.", enterEmail: 'Entrez votre courriel',
  billErrors: { bad_amount: "C'est plus que le solde. L'addition a été actualisée.", being_paid: "Quelqu'un à votre table paie en ce moment. Réessayez dans un instant.", no_open_check: 'Cette addition est déjà fermée.',
    online_pay_off: "Ce restaurant ne prend pas le paiement par téléphone. Payez votre serveur.", too_many_attempts: 'Trop d’essais. Payez votre serveur.', bad_tip: "Ce pourboire n'est pas permis.",
    gateway_error: "Le paiement n'est pas disponible. Réessayez dans un instant.", offline: 'Pas de connexion. Réessayez.', failed: "Ça n'a pas marché. Réessayez.", bad_email: 'Ce courriel ne semble pas valide.' },
  search: 'Rechercher au menu', noMatch: 'Aucun plat trouvé', ordered: (n) => `Commandé ×${n}`, from: '', soldOutShort: 'Épuisé',
  avoid: 'Mes allergies', avoidTitle: 'Ce que vous ne mangez pas', avoidHint: 'Les plats concernés ont une étiquette rouge ; ceux sans info sur les allergènes le disent.', avoidDone: 'OK', avoidClear: 'Effacer',
  hasAvoid: (a) => `Contient ${a}`, unknownAvoid: 'Allergènes non indiqués',
  stepSent: 'Envoyée', stepConfirm: 'Le serveur confirme', stepKitchen: 'En cuisine', orderNo: (n) => `Commande ${n}`,
  call: 'Appeler', callTitle: 'De quoi avez-vous besoin ?', callWater: 'De l’eau', callBill: "L'addition", callHelp: 'Un serveur',
  callSent: 'Un serveur est prévenu et arrive', callAck: 'Un serveur arrive', callCancel: "Annuler l'appel", callAgain: 'Rappeler',
  callErrors: { too_many_calls: 'Beaucoup d’appels ! Un serveur arrive', offline: 'Pas de connexion. Faites signe à un serveur.', failed: "L'appel n'est pas passé. Faites signe à un serveur." },
  poweredBy: 'Propulsé par', lang: 'Français', details: 'Détails', close: 'Fermer', ingredients: 'Ingrédients',
  yourTable: 'Votre table', pickLang: 'Langue', yourName: 'Votre nom (facultatif)', nameHint: 'Pour que la table voie qui a commandé quoi', start: 'Commencer',
  tableOrders: 'La commande de la table', checkNo: (n) => `Addition n° ${n}`, nothingYet: 'Rien de commandé à cette table', inKitchen: 'En cuisine', roundN: (n) => `Service ${n}`,
  orderMoreBtn: 'Commander encore', payBtn: 'Addition', sentBy: (n) => `Envoyée par ${n}`, me: 'Moi', guest: 'Invité',
  tableCart: 'Le panier de la table', clearCart: 'Vider', confirmClear: 'Tout vider ?', cartChanged: "Quelqu'un à votre table vient de modifier le panier. Vérifiez-le, puis envoyez.",
  cartErrors: { sold_out: 'Vient de s’épuiser : ', item_unavailable: "Ce plat n'est plus disponible", bad_option: 'Choisissez de nouveau les options : ', cart_full: 'Le panier est plein. Envoyez-en une partie.', offline: 'Pas de connexion. Réessayez.', failed: "Ça n'a pas été ajouté. Réessayez." },
  autoKitchen: 'Envoyée directement en cuisine', allCategories: 'Toutes les catégories', menuTitle: 'Réglages', language: 'Langue', changeName: 'Mon nom', saveName: 'Enregistrer',
  explain: 'Castor, c’est quoi ce plat ?', explaining: 'Castor explique…',
}

const ar = {
  scanFirst: 'امسح رمز QR على طاولتك للطلب', badCode: 'رمز QR هذا لم يعد صالحًا. يرجى سؤال النادل.', loading: 'جارٍ تحميل القائمة…', offline: 'لا يوجد اتصال. حاول مرة أخرى.',
  table: 'طاولة', all: 'الكل', soldOut: 'نفد', add: 'أضف', required: 'إلزامي', pickOne: 'اختر واحدًا', upTo: (n) => `حتى ${n}`, optional: 'اختياري',
  order: 'طلبك', items: (n) => `${n} أصناف`, subtotal: 'المجموع الفرعي', tax: 'الضريبة (تقديرية)', total: 'الإجمالي', note: 'ملاحظة للمطبخ', notePh: 'مثال: أقل حارًا، بدون بصل أخضر',
  itemNote: 'ملاحظة لهذا الطبق', send: 'اطلب', sending: 'جارٍ الإرسال…', save: 'حفظ', cancel: 'إلغاء',
  confirmHint: 'يذهب طلبك إلى المطبخ. اضغط «الحساب» لرؤيته ودفعه.', myOrders: 'طلباتك',
  pending: 'بانتظار تأكيد النادل', toKitchen: 'في الطريق إلى المطبخ', accepted: 'أُرسل إلى المطبخ', rejected: 'لم يُقبل', orderMore: 'اطلب المزيد',
  errors: { too_many_orders: 'طلبات كثيرة في وقت واحد. يرجى انتظار النادل.', sold_out: 'نفد للتو: ', item_unavailable: 'صنف لم يعد متاحًا. أعد تحميل القائمة.', bad_option: 'يرجى اختيار الخيارات مجددًا: ', unknown_table: 'رمز QR هذا لم يعد صالحًا. يرجى سؤال النادل.' },
  failed: 'لم يُرسل طلبك', retry: 'حاول مجددًا',
  contains: 'يحتوي على', noAllergenData: 'مسببات الحساسية غير مذكورة: اسأل النادل',
  guide: 'القندس', guideTitle: 'القندس · دليل القائمة الذكي', guideHello: 'مرحبًا! أنا القندس 🦫 اسألني بلغتك: ما هذا الطبق، هل هو حار، مسببات الحساسية، كم تطلبون.',
  guideHint: 'مثال: عندي حساسية من الفول السوداني، ماذا آكل؟', ask: 'إرسال', listening: 'أستمع… اضغط للإيقاف', addSuggestion: 'أضف',
  guideErrors: { busy: 'القندس مشغول، حاول بعد نصف دقيقة', table_limit: 'أسئلة كثيرة! انتظر لحظة', store_limit: 'انتهت أسئلة اليوم، اسأل النادل', no_model: 'الدليل الذكي غير متاح، اسأل النادل', offline: 'لا يوجد اتصال، حاول مجددًا', failed: 'لم أفهم، جرّب صياغة أخرى', no_speech_service: 'الصوت غير متاح، اكتب من فضلك', too_long: 'التسجيل طويل جدًا' },
  aiNote: 'إجابات الذكاء الاصطناعي للإرشاد فقط. أخبر النادل دائمًا بالحساسية.',
  voiceTitle: 'تحدّث إلى القندس', voiceEntry: 'تحدّث إلى القندس', voiceEntrySub: 'اطلب أو اسأل، بجملة واحدة',
  voiceHint: 'اضغط الميكروفون: اطلب أو اسأل', voiceExample: 'مثال: طبقا نودلز باللحم، واحد بدون بصل · ما الأطباق غير الحارة؟',
  voiceHearing: 'أكتب ما قلت…', voiceThinking: 'القندس يراجع القائمة…', voiceNothing: 'لم أسمع شيئًا، حاول مجددًا',
  voiceNoLines: 'لم أجد ذلك في القائمة. جرّب بطريقة أخرى؟', youSaid: 'قلت', fromMenu: 'من قائمة هذا المطعم · القندس',
  whichOne: (h) => `أي «${h}»؟`, notOnMenu: 'ليس في القائمة:', pickOptions: 'اختر الخيارات',
  locale: 'ar', partyTitle: 'كم عددكم؟', partySpice: 'حار؟', spice: { none: 'بدون حار', mild: 'خفيف لا بأس', any: 'أي شيء' }, partyHint: 'اختياري: القندس يختار حسب طاولتكم',
  peopleN: (n) => `${n} ${n === 1 ? 'شخص' : 'أشخاص'}`, picksTitle: (n) => `اختيارات القندس لـ ${n}`, picksBtn: 'دع القندس يجهّز طاولتكم', picksGo: 'ابدأ', picksLoading: 'القندس يختار…',
  picksAgain: 'غيرها', picksAll: (m) => `أضف الكل · ${m}`, partyDone: 'تم',
  enoughBtn: 'اسأل القندس: هل هذا يكفي؟', enoughNeedSize: 'كم عددكم؟', enoughThinking: 'القندس ينظر…', enoughAgain: 'انظر مجددًا',
  verdict: { enough: 'يكفي', more: 'ينقص قليلًا', less: 'كثير' },
  addAll: (n, m) => `أضف الكل إلى السلة · ${n} بـ ${m}`, addedN: (n) => `أُضيف ${n}`, sayMore: 'المزيد', typeInstead: 'اكتب', tapToStop: 'اضغط عند الانتهاء',
  bill: 'الحساب', billTitle: 'الحساب', noBill: 'لا يوجد حساب لهذه الطاولة بعد', taxActual: 'الضريبة', alreadyPaid: 'مدفوع', balance: 'المتبقي',
  payServer: 'يرجى الدفع للنادل', allPaid: 'تم دفع حساب هذه الطاولة. شكرًا!', howMuch: 'كم', payAll: 'المبلغ كله', split: 'قسّم بالتساوي', customAmount: 'مبلغ آخر',
  perPerson: (m) => `${m} لكل شخص`, tipTitle: 'البقشيش', tipBasis: (m) => `من ${m} قبل الضريبة`, customTip: 'مخصص', noTip: 'بدون بقشيش',
  chooseTip: 'اختر البقشيش أولًا', payButton: (m) => `ادفع ${m}`, payNow: 'ادفع', confirming: 'جارٍ تأكيد الدفع…',
  paidOk: (m) => `تم دفع ${m}. شكرًا!`, stillOwed: (m) => `ما زال ${m} مستحقًا على هذه الطاولة`, payMore: 'ادفع حصة أخرى',
  payFailed: 'لم تتم عملية الدفع. لم يُخصم منك شيء.', payReview: 'يحتاج النادل إلى مراجعة هذا الدفع. أرِه هذه الشاشة.',
  payPending: 'لم يصل التأكيد بعد. لا تدفع مرة أخرى: اسأل النادل.', nuveiLocale: 'ar',
  emailLabel: 'البريد الإلكتروني للإيصال', emailHint: 'يُرسل فقط إلى Nuvei، شركة الدفع، لهذا الدفع. المطعم لا يحتفظ به.', enterEmail: 'أدخل بريدك الإلكتروني',
  billErrors: { bad_amount: 'هذا أكثر من المتبقي. تم تحديث الحساب.', being_paid: 'شخص على طاولتك يدفع الآن. حاول بعد لحظة.', no_open_check: 'هذا الحساب مغلق بالفعل.',
    online_pay_off: 'هذا المطعم لا يقبل الدفع عبر الهاتف. يرجى الدفع للنادل.', too_many_attempts: 'محاولات كثيرة. يرجى الدفع للنادل.', bad_tip: 'مبلغ البقشيش غير مسموح.',
    gateway_error: 'الدفع غير متاح الآن. حاول بعد لحظة.', offline: 'لا يوجد اتصال. حاول مجددًا.', failed: 'لم ينجح ذلك. حاول مجددًا.', bad_email: 'البريد الإلكتروني لا يبدو صحيحًا.' },
  search: 'ابحث في القائمة', noMatch: 'لا يوجد طبق مطابق', ordered: (n) => `طُلب ×${n}`, from: '', soldOutShort: 'نفد',
  avoid: 'حساسيتي', avoidTitle: 'ما لا تأكله', avoidHint: 'الأطباق التي تحتوي عليها تظهر بعلامة حمراء؛ والتي بلا معلومات تقول ذلك.', avoidDone: 'تم', avoidClear: 'مسح',
  hasAvoid: (a) => `يحتوي على ${a}`, unknownAvoid: 'مسببات الحساسية غير مذكورة',
  stepSent: 'أُرسل', stepConfirm: 'النادل يؤكد', stepKitchen: 'في المطبخ', orderNo: (n) => `الطلب ${n}`,
  call: 'نداء', callTitle: 'ماذا تحتاج؟', callWater: 'ماء', callBill: 'جاهز للدفع', callHelp: 'نادل',
  callSent: 'تم إبلاغ النادل وهو قادم', callAck: 'النادل في الطريق', callCancel: 'إلغاء النداء', callAgain: 'نادِ مجددًا',
  callErrors: { too_many_calls: 'نداءات كثيرة! النادل قادم', offline: 'لا يوجد اتصال. لوّح للنادل.', failed: 'لم يصل النداء. لوّح للنادل.' },
  poweredBy: 'بدعم من', lang: 'العربية', details: 'التفاصيل', close: 'إغلاق', ingredients: 'المكونات',
  yourTable: 'طاولتك', pickLang: 'اللغة', yourName: 'اسمك (اختياري)', nameHint: 'ليرى من على الطاولة من طلب ماذا', start: 'ابدأ الطلب',
  tableOrders: 'طلب الطاولة', checkNo: (n) => `حساب رقم ${n}`, nothingYet: 'لم يُطلب شيء على هذه الطاولة بعد', inKitchen: 'في المطبخ', roundN: (n) => `الجولة ${n}`,
  orderMoreBtn: 'اطلب المزيد', payBtn: 'الحساب', sentBy: (n) => `أرسله ${n}`, me: 'أنا', guest: 'ضيف',
  tableCart: 'سلة الطاولة', clearCart: 'إفراغ', confirmClear: 'إفراغ الكل؟', cartChanged: 'شخص على طاولتك غيّر السلة للتو. راجعها ثم أرسل.',
  cartErrors: { sold_out: 'نفد للتو: ', item_unavailable: 'هذا الطبق لم يعد متاحًا', bad_option: 'يرجى اختيار الخيارات مجددًا: ', cart_full: 'السلة ممتلئة. أرسل بعضها أولًا.', offline: 'لا يوجد اتصال. حاول مجددًا.', failed: 'لم تتم الإضافة. حاول مجددًا.' },
  autoKitchen: 'أُرسل مباشرة إلى المطبخ', allCategories: 'كل الفئات', menuTitle: 'الإعدادات', language: 'اللغة', changeName: 'اسمي', saveName: 'حفظ',
  explain: 'أيها القندس، ما هذا الطبق؟', explaining: 'القندس يشرح…',
}

const ALLERGENS_FR = { peanut: 'arachides', tree_nut: 'noix', milk: 'lait', egg: 'œufs', wheat: 'blé/gluten', soy: 'soya', fish: 'poisson', crustacean: 'crustacés', mollusc: 'mollusques', sesame: 'sésame', mustard: 'moutarde', sulphite: 'sulfites' }
const ALLERGENS_AR = { peanut: 'الفول السوداني', tree_nut: 'المكسرات', milk: 'الحليب', egg: 'البيض', wheat: 'القمح/الغلوتين', soy: 'الصويا', fish: 'السمك', crustacean: 'القشريات', mollusc: 'الرخويات', sesame: 'السمسم', mustard: 'الخردل', sulphite: 'الكبريتات' }
const DIETARY_FR = { vegetarian: '🌱 Végétarien', vegan: '🌿 Végane', halal: '☪ Halal', gluten_free: 'Sans gluten', no_pork: 'Sans porc', no_beef: 'Sans bœuf' }
const DIETARY_AR = { vegetarian: '🌱 نباتي', vegan: '🌿 نباتي صرف', halal: '☪ حلال', gluten_free: 'خالٍ من الغلوتين', no_pork: 'بدون لحم خنزير', no_beef: 'بدون لحم بقر' }

// Canada's priority allergens (products.attributes.allergens codes) and diet labels (attributes.dietary).
const ALLERGENS_ZH = { peanut: '花生', tree_nut: '坚果', milk: '奶', egg: '蛋', wheat: '小麦/麸质', soy: '大豆', fish: '鱼', crustacean: '甲壳类', mollusc: '贝类', sesame: '芝麻', mustard: '芥末', sulphite: '亚硫酸盐' }
const ALLERGENS_EN = { peanut: 'peanut', tree_nut: 'tree nuts', milk: 'milk', egg: 'egg', wheat: 'wheat/gluten', soy: 'soy', fish: 'fish', crustacean: 'shellfish', mollusc: 'molluscs', sesame: 'sesame', mustard: 'mustard', sulphite: 'sulphites' }
const DIETARY_ZH = { vegetarian: '🌱 素食', vegan: '🌿 纯素', halal: '☪ 清真', gluten_free: '无麸质', no_pork: '不含猪肉', no_beef: '不含牛肉' }
const DIETARY_EN = { vegetarian: '🌱 Vegetarian', vegan: '🌿 Vegan', halal: '☪ Halal', gluten_free: 'Gluten-free', no_pork: 'No pork', no_beef: 'No beef' }

export const T = { zh: zhT, en, fr, ar }[LANG]
export const ALLERGENS = { zh: ALLERGENS_ZH, en: ALLERGENS_EN, fr: ALLERGENS_FR, ar: ALLERGENS_AR }[LANG]
export const DIETARY = { zh: DIETARY_ZH, en: DIETARY_EN, fr: DIETARY_FR, ar: DIETARY_AR }[LANG]
/** A list joined the way the language does it. */
export const joinList = (xs) => xs.join(zh ? '、' : RTL ? '، ' : ', ')

/** A dish's name in the customer's language (Cael's attributes.name_i18n), else the store's own. */
export const dishName = (p) => (p?.names && typeof p.names[LANG] === 'string' && p.names[LANG].trim()) || p?.name || ''
