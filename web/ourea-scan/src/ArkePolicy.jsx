import React, { useEffect, useState } from 'react'
import { ArrowLeft, Loader2, Phone, MapPin, ShieldCheck, Receipt } from 'lucide-react'
import { LANG } from './i18n.js'
import { supabase } from './lib.js'
import { Signature } from './shared.jsx'

/*
 * Arke's privacy policy (?s=<slug>&p=privacy) and cancellation & refund policy (?s=<slug>&p=refund),
 * per store (2026-11-19, docs/arke_go_live.md). Canada's PIPEDA wants a privacy notice where we take
 * names, phones, emails and addresses. Every sentence says what this site and its backend actually
 * do -- change the words when the behaviour changes, never the other way round:
 *  - card numbers go into Nuvei's form only; we keep the card brand, last 4 digits and approval;
 *  - the browser keeps language, name, phone, email, address, cart and order links (lib.js store, pay.jsx);
 *  - no ad / analytics trackers on this site;
 *  - data lives in Supabase ca-central-1 (Canada);
 *  - declined or not accepted within accept_minutes -> cancelled, everything back, email
 *    (2026-11-19_arke_accept_deadline.sql); void the same day, else refund 3-10 business days;
 *  - once accepted, no online cancel (cmp_online_refund_org: REJECTED orders only) -> call the store;
 *  - unpaid orders expire after 30 minutes (online_order_status); points of a cancelled order go.
 * Not legal advice: the owner has a lawyer read it before the first real store (docs/arke_go_live.md).
 */

const UPDATED = '2026-11-19'

const zh = {
  back: '返回菜单', updated: (d) => `更新日期：${d}`, contact: '联系店家', loading: '正在加载…', bad: '找不到这家店',
  privacy: {
    title: '隐私政策',
    intro: (s) => `${s} 通过 GoldSky Arke 接受网上点餐。这里说明你下单时我们收集什么、为什么收集、谁能看到。你的订单和资料由 ${s} 负责，GoldSky 替店家运营这个点餐服务。`,
    sections: (s) => [
      ['我们收集什么', [
        '姓名和电话：店家用来认出你的订单，有问题时联系你。',
        '电子邮箱：发订单确认、取消和退款的邮件。',
        '选择配送时：送餐地址和送餐备注。',
        '你点了什么、备注、小费、优惠码，以及付款结果（是否批准、卡的品牌和后 4 位）。',
        '我们不会收到也不会保存你的完整卡号：卡号只在 Nuvei 的安全付款表单里输入。',
      ]],
      ['用来做什么', [
        '做餐、交给你或送到你手上，并告诉你订单进度。',
        '发订单邮件；订单取消时把钱退回去。',
        ...(s.points ? ['如果你选择积分：用电话号码给你记店家的会员积分。'] : []),
        '店家的营业和税务记录。',
      ]],
      ['谁能看到', [
        `${s.name} 的员工。`,
        'GoldSky：运营和维护这个点餐服务。',
        'Nuvei：处理刷卡付款。',
        '我们的邮件服务商：发送订单邮件。',
        '我们不出售你的资料，也不用来做广告。',
      ]],
      ['保存在你的手机或电脑上', [
        '为了下次方便，这个网站会在你的浏览器里记住：语言、姓名、电话、邮箱、送餐地址、购物车和你下过的订单链接。',
        '这个网站没有广告或统计追踪。你可以随时在浏览器设置里清除本网站的数据。',
      ]],
      ['存在哪里、存多久', [
        '订单资料存放在加拿大境内的服务器上。',
        '保存时间以店家营业记录和法律（例如税务）要求为准。',
      ]],
      ['你的权利', [
        '你可以请店家查看、更正或删除你的资料（法律要求店家保留的记录除外）。请用下面的方式联系店家。',
      ]],
    ],
  },
  refund: {
    title: '取消和退款政策',
    intro: (s) => `在 ${s} 网上点餐时，订单什么时候能取消、钱怎么退回，都在这里。`,
    sections: (s) => [
      ['付款', [
        '下单时用卡付款，付款成功后店家才会收到订单。',
        '付款没完成，就不算下单，也不会扣钱；没付款的订单 30 分钟后自动失效。',
      ]],
      ['店家没接单', [
        `店家拒单，或者 ${s.accept} 分钟内没人确认（预约单按开始做餐的时间算），订单会自动取消，钱全额退回，包括小费。我们会发邮件告诉你。`,
      ]],
      ['钱什么时候回来', [
        '当天取消的：付款直接撤销，不会扣款（银行可能暂时显示一笔待处理金额，几天内消失）。',
        '其他情况：退回到原来的卡上，一般 3–10 个工作日到账，视发卡行而定。',
      ]],
      ['店家接单以后', [
        '店家接单后就开始做了，网上不能再取消。',
        `要改单、取消，或者订单有问题（漏了、拿错、质量问题），请尽快打电话给店家${s.phone ? `（${s.phone}）` : ''}，由店家决定怎么处理，比如重做或退款。`,
      ]],
      ...(s.points ? [['积分', ['订单取消后，这笔订单的积分会被收回。']]] : []),
    ],
  },
}

const en = {
  back: 'Back to menu', updated: (d) => `Last updated: ${d}`, contact: 'Contact the store', loading: 'Loading…', bad: 'Store not found',
  privacy: {
    title: 'Privacy policy',
    intro: (s) => `${s} takes online orders with GoldSky Arke. This page explains what we collect when you order, why, and who sees it. ${s} is responsible for your order and your information; GoldSky runs the ordering service for the store.`,
    sections: (s) => [
      ['What we collect', [
        'Your name and phone number, so the store can match the order to you and reach you about it.',
        'Your email, for the order confirmation, cancellation and refund emails.',
        'For delivery: the address and any delivery instructions.',
        'What you order, notes, tip, coupon, and the payment result (approved or not, card brand and last 4 digits).',
        'We never receive or keep your full card number: you type it only into Nuvei’s secure payment form.',
      ]],
      ['Why', [
        'To prepare your order, hand it over or deliver it, and show you where it is.',
        'To send order emails, and to give your money back if the order is cancelled.',
        ...(s.points ? ['If you choose to collect points: to add the store’s reward points to your phone number.'] : []),
        'For the store’s business and tax records.',
      ]],
      ['Who sees it', [
        `Staff of ${s.name}.`,
        'GoldSky, which runs and supports the ordering service.',
        'Nuvei, which processes the card payment.',
        'Our email provider, which sends the order emails.',
        'We don’t sell your information or use it for advertising.',
      ]],
      ['On your phone or computer', [
        'To save you typing next time, this site remembers in your browser: language, name, phone, email, delivery address, cart, and links to your orders.',
        'This site has no advertising or analytics trackers. You can clear this site’s data in your browser settings at any time.',
      ]],
      ['Where and how long', [
        'Order information is stored on servers in Canada.',
        'It is kept as long as the store needs it for its records and as the law (for example tax rules) requires.',
      ]],
      ['Your choices', [
        'You can ask the store to show, correct or delete your information, except records the law requires it to keep. Use the contact details below.',
      ]],
    ],
  },
  refund: {
    title: 'Cancellation & refund policy',
    intro: (s) => `When an order placed online at ${s} can be cancelled, and how your money comes back.`,
    sections: (s) => [
      ['Paying', [
        'You pay by card when you place the order; the store only gets the order once the payment is approved.',
        'If the payment doesn’t go through, no order is placed and nothing is charged. Unpaid orders expire after 30 minutes.',
      ]],
      ['If the store can’t take it', [
        `If the store declines your order, or nobody confirms it within ${s.accept} minutes (for a scheduled order: before it should start being prepared), it is cancelled automatically and you get all of your money back, tip included. We email you when that happens.`,
      ]],
      ['When the money shows', [
        'Cancelled the same day: the payment is cancelled and you are not charged (your bank may show a pending amount for a few days).',
        'Otherwise: refunded to the same card, usually within 3–10 business days, depending on your bank.',
      ]],
      ['After the store accepts', [
        'Once the store accepts, it starts preparing your order, so it can no longer be cancelled online.',
        `To change or cancel it, or if something is wrong (missing or wrong items, quality), call the store as soon as you can${s.phone ? ` at ${s.phone}` : ''}. The store decides how to fix it, for example by remaking the item or giving a refund.`,
      ]],
      ...(s.points ? [['Points', ['Points earned on an order that is cancelled are removed.']]] : []),
    ],
  },
}

const fr = {
  back: 'Retour au menu', updated: (d) => `Dernière mise à jour : ${d}`, contact: 'Contacter le commerce', loading: 'Chargement…', bad: 'Commerce introuvable',
  privacy: {
    title: 'Politique de confidentialité',
    intro: (s) => `${s} prend les commandes en ligne avec GoldSky Arke. Cette page explique ce que nous recueillons quand vous commandez, pourquoi, et qui y a accès. ${s} est responsable de votre commande et de vos renseignements; GoldSky exploite le service de commande pour le commerce.`,
    sections: (s) => [
      ['Ce que nous recueillons', [
        'Votre nom et votre numéro de téléphone, pour associer la commande à vous et vous joindre à son sujet.',
        'Votre courriel, pour les courriels de confirmation, d’annulation et de remboursement.',
        'Pour la livraison : l’adresse et les instructions de livraison.',
        'Ce que vous commandez, les notes, le pourboire, le coupon, et le résultat du paiement (approuvé ou non, marque de la carte et 4 derniers chiffres).',
        'Nous ne recevons ni ne conservons jamais votre numéro de carte complet : vous le saisissez uniquement dans le formulaire de paiement sécurisé de Nuvei.',
      ]],
      ['Pourquoi', [
        'Pour préparer votre commande, vous la remettre ou la livrer, et vous montrer où elle en est.',
        'Pour envoyer les courriels de commande et vous rembourser si la commande est annulée.',
        ...(s.points ? ['Si vous choisissez d’accumuler des points : pour ajouter les points du commerce à votre numéro de téléphone.'] : []),
        'Pour les registres d’affaires et fiscaux du commerce.',
      ]],
      ['Qui y a accès', [
        `Le personnel de ${s.name}.`,
        'GoldSky, qui exploite et soutient le service de commande.',
        'Nuvei, qui traite le paiement par carte.',
        'Notre fournisseur de courriel, qui envoie les courriels de commande.',
        'Nous ne vendons pas vos renseignements et ne les utilisons pas pour la publicité.',
      ]],
      ['Sur votre téléphone ou ordinateur', [
        'Pour vous éviter de tout retaper, ce site garde dans votre navigateur : la langue, le nom, le téléphone, le courriel, l’adresse de livraison, le panier et les liens vers vos commandes.',
        'Ce site n’a aucun traceur publicitaire ou analytique. Vous pouvez effacer les données de ce site dans les réglages de votre navigateur.',
      ]],
      ['Où et combien de temps', [
        'Les renseignements de commande sont conservés sur des serveurs au Canada.',
        'Ils sont gardés aussi longtemps que le commerce en a besoin pour ses registres et que la loi (par exemple les règles fiscales) l’exige.',
      ]],
      ['Vos choix', [
        'Vous pouvez demander au commerce de consulter, corriger ou supprimer vos renseignements, sauf les registres que la loi l’oblige à garder. Utilisez les coordonnées ci-dessous.',
      ]],
    ],
  },
  refund: {
    title: 'Politique d’annulation et de remboursement',
    intro: (s) => `Quand une commande passée en ligne chez ${s} peut être annulée, et comment votre argent vous revient.`,
    sections: (s) => [
      ['Paiement', [
        'Vous payez par carte en passant la commande; le commerce ne reçoit la commande qu’une fois le paiement approuvé.',
        'Si le paiement n’aboutit pas, aucune commande n’est passée et rien n’est débité. Les commandes non payées expirent après 30 minutes.',
      ]],
      ['Si le commerce ne peut pas la prendre', [
        `Si le commerce refuse votre commande, ou si personne ne la confirme dans les ${s.accept} minutes (commande planifiée : avant le moment où elle doit être préparée), elle est annulée automatiquement et vous êtes remboursé en entier, pourboire compris. Nous vous l’annonçons par courriel.`,
      ]],
      ['Quand l’argent revient', [
        'Annulée le jour même : le paiement est annulé et vous n’êtes pas débité (votre banque peut afficher un montant en attente quelques jours).',
        'Sinon : remboursée sur la même carte, généralement sous 3 à 10 jours ouvrables selon votre banque.',
      ]],
      ['Après l’acceptation', [
        'Une fois la commande acceptée, le commerce commence à la préparer : elle ne peut plus être annulée en ligne.',
        `Pour la modifier ou l’annuler, ou en cas de problème (article manquant ou erroné, qualité), appelez le commerce dès que possible${s.phone ? ` au ${s.phone}` : ''}. Le commerce décide de la solution, par exemple refaire l’article ou rembourser.`,
      ]],
      ...(s.points ? [['Points', ['Les points d’une commande annulée sont retirés.']]] : []),
    ],
  },
}

const ar = {
  back: 'العودة إلى القائمة', updated: (d) => `آخر تحديث: ${d}`, contact: 'التواصل مع المتجر', loading: 'جارٍ التحميل…', bad: 'المتجر غير موجود',
  privacy: {
    title: 'سياسة الخصوصية',
    intro: (s) => `يستقبل ${s} الطلبات عبر الإنترنت باستخدام GoldSky Arke. توضح هذه الصفحة ما نجمعه عند الطلب، ولماذا، ومن يطّلع عليه. ${s} مسؤول عن طلبك وبياناتك، وتشغّل GoldSky خدمة الطلب نيابةً عن المتجر.`,
    sections: (s) => [
      ['ما نجمعه', [
        'اسمك ورقم هاتفك، ليتعرّف المتجر على طلبك ويتواصل معك بشأنه.',
        'بريدك الإلكتروني، لرسائل تأكيد الطلب وإلغائه واسترداد المبلغ.',
        'عند التوصيل: العنوان وتعليمات التوصيل.',
        'ما تطلبه والملاحظات والإكرامية والقسيمة، ونتيجة الدفع (مقبول أم لا، نوع البطاقة وآخر 4 أرقام).',
        'لا نستلم رقم بطاقتك الكامل ولا نحتفظ به أبدًا: تكتبه فقط في نموذج الدفع الآمن من Nuvei.',
      ]],
      ['لماذا', [
        'لتحضير طلبك وتسليمه أو توصيله، وإظهار مرحلته لك.',
        'لإرسال رسائل الطلب، وإعادة مالك إذا أُلغي الطلب.',
        ...(s.points ? ['إذا اخترت جمع النقاط: لإضافة نقاط مكافآت المتجر إلى رقم هاتفك.'] : []),
        'لسجلات المتجر التجارية والضريبية.',
      ]],
      ['من يطّلع عليها', [
        `موظفو ${s.name}.`,
        'GoldSky، التي تشغّل خدمة الطلب وتدعمها.',
        'Nuvei، التي تعالج الدفع بالبطاقة.',
        'مزوّد البريد لدينا، الذي يرسل رسائل الطلب.',
        'لا نبيع بياناتك ولا نستخدمها للإعلانات.',
      ]],
      ['على هاتفك أو حاسوبك', [
        'لتوفير الوقت في المرة القادمة، يحفظ هذا الموقع في متصفحك: اللغة والاسم والهاتف والبريد وعنوان التوصيل والسلة وروابط طلباتك.',
        'لا يحتوي هذا الموقع على أدوات تتبّع إعلانية أو تحليلية. يمكنك مسح بيانات الموقع من إعدادات المتصفح في أي وقت.',
      ]],
      ['أين ولأي مدة', [
        'تُحفظ بيانات الطلب على خوادم داخل كندا.',
        'وتُحتفظ بها ما دام المتجر يحتاجها لسجلاته وكما يقتضي القانون (مثل القواعد الضريبية).',
      ]],
      ['خياراتك', [
        'يمكنك أن تطلب من المتجر الاطلاع على بياناتك أو تصحيحها أو حذفها، باستثناء السجلات التي يلزمه القانون بالاحتفاظ بها. استخدم بيانات التواصل أدناه.',
      ]],
    ],
  },
  refund: {
    title: 'سياسة الإلغاء والاسترداد',
    intro: (s) => `متى يمكن إلغاء طلب عبر الإنترنت من ${s}، وكيف يعود إليك مالك.`,
    sections: (s) => [
      ['الدفع', [
        'تدفع بالبطاقة عند الطلب، ولا يصل الطلب إلى المتجر إلا بعد قبول الدفع.',
        'إذا لم يكتمل الدفع فلا يُسجَّل طلب ولا يُخصم شيء. تنتهي الطلبات غير المدفوعة بعد 30 دقيقة.',
      ]],
      ['إذا لم يستطع المتجر قبوله', [
        `إذا رفض المتجر طلبك، أو لم يؤكده أحد خلال ${s.accept} دقيقة (للطلب المجدول: قبل موعد بدء تحضيره)، يُلغى تلقائيًا ويُعاد إليك المبلغ كاملًا مع الإكرامية، ونخبرك بالبريد.`,
      ]],
      ['متى يعود المال', [
        'إذا أُلغي في اليوم نفسه: تُلغى الدفعة ولا يُخصم منك شيء (قد يظهر مبلغ معلّق لبضعة أيام).',
        'غير ذلك: يُعاد إلى البطاقة نفسها، عادةً خلال 3–10 أيام عمل حسب البنك.',
      ]],
      ['بعد قبول المتجر', [
        'بعد أن يقبل المتجر طلبك يبدأ تحضيره، فلا يمكن إلغاؤه عبر الإنترنت.',
        `لتعديله أو إلغائه، أو إذا كانت هناك مشكلة (صنف ناقص أو خاطئ، الجودة)، اتصل بالمتجر في أقرب وقت${s.phone ? ` على ${s.phone}` : ''}. يقرّر المتجر طريقة الحل، مثل إعادة تحضير الصنف أو استرداد المبلغ.`,
      ]],
      ...(s.points ? [['النقاط', ['تُزال نقاط الطلب الملغى.']]] : []),
    ],
  },
}

const P = { zh, en, fr, ar }[LANG] || en

/** "Refund policy · Privacy" links for a store's pages (footer, checkout). */
export function PolicyLinks({ slug, className = '' }) {
  if (!slug) return null
  return (
    <p className={`flex items-center justify-center gap-3 text-[11px] text-ink-mute ${className}`}>
      <a href={`?s=${slug}&p=refund`} className="underline underline-offset-2">{P.refund.title}</a>
      <span aria-hidden>·</span>
      <a href={`?s=${slug}&p=privacy`} className="underline underline-offset-2">{P.privacy.title}</a>
    </p>
  )
}

export default function ArkePolicy({ slug, page }) {
  const [s, setS] = useState(null)
  const [state, setState] = useState('loading')
  const kind = page === 'privacy' ? 'privacy' : 'refund'
  const doc = P[kind]

  useEffect(() => {
    supabase.rpc('online_menu', { p_slug: slug }).then(({ data, error }) => {
      if (error || !data) { setState('bad'); return }
      setS(data.store); setState('ready')
      document.title = `${doc.title} · ${data.store.name}`
    })
  }, [slug, doc.title])

  if (state !== 'ready') {
    return (
      <div className="min-h-screen flex items-center justify-center bg-paper text-ink-soft font-bold gap-2">
        {state === 'loading' ? <><Loader2 className="w-5 h-5 animate-spin" />{P.loading}</> : P.bad}
      </div>
    )
  }
  const facts = { name: s.name, phone: s.phone, points: !!s.points, accept: s.accept_minutes ?? 15 }
  const Icon = kind === 'privacy' ? ShieldCheck : Receipt
  return (
    <div className="min-h-screen bg-paper">
      <div className="max-w-2xl mx-auto px-4 py-6">
        <a href={`?s=${slug}`} className="inline-flex items-center gap-1.5 text-sm font-black text-gold-800">
          <ArrowLeft className="w-4 h-4 rtl:rotate-180" />{P.back}</a>
        <div className="mt-5 bg-white rounded-3xl shadow-card p-5 sm:p-7">
          <div className="flex items-center gap-3">
            <div className="w-11 h-11 rounded-2xl bg-gold-50 flex items-center justify-center shrink-0"><Icon className="w-6 h-6 text-gold-700" /></div>
            <div className="min-w-0">
              <h1 className="font-black text-2xl text-ink leading-tight">{doc.title}</h1>
              <p className="text-sm text-ink-mute">{s.name}</p>
            </div>
          </div>
          <p className="mt-5 text-ink-soft leading-relaxed">{doc.intro(s.name)}</p>
          {doc.sections(facts).map(([h, items]) => (
            <section key={h} className="mt-6">
              <h2 className="font-black text-lg text-ink mb-2">{h}</h2>
              <ul className="space-y-1.5 list-disc ps-5 text-ink-soft leading-relaxed">
                {items.map((t) => <li key={t}>{t}</li>)}
              </ul>
            </section>
          ))}
          <section className="mt-7 pt-5 border-t border-slate-100">
            <h2 className="font-black text-lg text-ink mb-2">{P.contact}</h2>
            <p className="font-bold text-ink">{s.name}</p>
            {s.address && <p className="flex items-start gap-2 text-sm text-ink-soft mt-1"><MapPin className="w-4 h-4 mt-0.5 shrink-0 text-gold-700" />{s.address}</p>}
            {s.phone && <a href={`tel:${s.phone.replace(/[^0-9+]/g, '')}`} className="flex items-center gap-2 text-sm font-black text-sky-700 mt-1"><Phone className="w-4 h-4" />{s.phone}</a>}
          </section>
          <p className="mt-6 text-xs text-ink-mute">{P.updated(UPDATED)}</p>
        </div>
        <PolicyLinks slug={slug} className="mt-6" />
        <Signature product="Arke" className="mt-3 mb-4" />
      </div>
    </div>
  )
}
