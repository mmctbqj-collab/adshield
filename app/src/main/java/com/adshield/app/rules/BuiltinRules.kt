package com.adshield.app.rules

/**
 * 内置域名规则库。
 *
 * 分三组：
 * - [ADS]      广告 SDK / 广告平台域名，默认启用，覆盖主流国内外广告变现平台；
 * - [DOH]      加密 DNS（DoH/DoT）服务域名，**默认关闭**，由用户主动开启；
 * - [TRACKING] 统计与归因类域名，**默认不内置启用**（易影响 App 功能），仅列出供参考。
 *
 * 全部按后缀匹配：`ad.qq.com` 同时覆盖 `x.ad.qq.com`。
 *
 * 说明：
 * - 本库为示例集，不承诺覆盖率与时效性，用户可在设置页自行增删；
 * - 不收录会明显误伤的通用域名（如 `googleapis.com`、`gstatic.com`、`cloudfront.net`、
 *   `graph.facebook.com`、`connect.facebook.net`、`branch.io`），否则会导致 App 功能异常。
 */
object BuiltinRules {

    val ADS: String = """
# ================= Google / AdMob / 广告验证 =================
googleads.g.doubleclick.net
pagead2.googlesyndication.com
tpc.googlesyndication.com
securepubads.g.doubleclick.net
ad.doubleclick.net
stats.g.doubleclick.net
adservice.google.com
googleadservices.com
googlesyndication.com
googletagservices.com
googletagmanager.com
app-measurement.com
adsafeprotected.com
moatads.com
doubleclick.net
2mdn.net

# ================= Meta Audience Network =================
an.facebook.com
ads.facebook.com

# ================= 字节跳动 / 穿山甲 / Pangle =================
ad.oceanengine.com
pangolin-sdk-toutiao.com
api.pangolin-sdk-toutiao.com
tlog.pangolin-sdk-toutiao.com
gromore.pangolin-sdk-toutiao.com
pglstatp-toutiao.com
pangle.io
api.pangle.io

# ================= 腾讯 / 优量汇 =================
ad.qq.com
gdt.qq.com
pgdt.gtimg.cn
e.qq.com

# ================= 阿里 / 阿里妈妈 / 友盟 =================
alog.umeng.com
alog.umengcloud.com
ulogs.umeng.com
ulogs.umengcloud.com
tanx.com
atanx.alicdn.com
umeng.com

# ================= 百度联盟 =================
mobads.baidu.com
mobads-logs.baidu.com
pos.baidu.com
cpro.baidu.com
union.baidu.com
afd.baidu.com

# ================= 国内厂商广告平台 =================
api.ad.xiaomi.com
ad.xiaomi.com
admaster.com.cn
adx.ads.oppomobile.com
ads.oppomobile.com
data.ads.oppomobile.com
ads.vivo.com.cn
adx.vivo.com.cn
ads.huawei.com
ads.honor.com
samsungads.com

# ================= Mintegral / Mobvista =================
mbridge.mintegral.com
net.mintegral.com
ad.mintegral.com
api.mintegral.com
t.mintegral.com
mintegral.com

# ================= Unity Ads =================
ads.unity3d.com
api.unityads.unity3d.com
config.unityads.unity3d.com
unityads.unity3d.com

# ================= AppLovin =================
api.applovin.com
d.applvn.com
ms.applvn.com
rt.applvn.com
applovin.com
assets.applovin.com

# ================= Vungle / Liftoff =================
ads.vungle.com
api.vungle.com
vungle.com
liftoff.io
api.liftoff.io

# ================= ironSource / LevelPlay =================
api.ironsrc.com
is-gateway.ironsrc.com
auction.ironsrc.com
ironsrc.com

# ================= InMobi =================
api.inmobi.com
w.inmobi.com
et.w.inmobi.com
inmobi.com

# ================= Chartboost =================
live.chartboost.com
chartboost.com
api.chartboost.com

# ================= Tapjoy =================
ws.tapjoyads.com
api.tapjoy.com
tapjoy.com

# ================= AdColony =================
adcolony.com
ads30.adcolony.com
api.adcolony.com

# ================= Amazon Ads =================
aax.amazon-adsystem.com
mads.amazon-adsystem.com
amazon-adsystem.com

# ================= 海外 DSP / 交易平台 =================
static.criteo.net
dis.criteo.com
criteo.com
smaato.com
smaato.net
pubmatic.com
rubiconproject.com
openx.net
taboola.com
outbrain.com

# ================= 其它广告 SDK =================
api.appnext.com
appnext.com
start.io
api.start.io
api.fyber.com
fyber.com
digitalturbine.com
    """.trimIndent()

    /**
     * 加密 DNS 域名。拦截后相关 App/浏览器会回退到系统 DNS（也就是我们的本地过滤），
     * 从而让拦截重新生效；副作用是用户主动配置过这些 DoH 的服务会失效。
     */
    val DOH: String = """
dns.google
cloudflare-dns.com
mozilla.cloudflare-dns.com
chrome.cloudflare-dns.com
dns.adguard-dns.com
dns.adguard.com
doh.opendns.com
dns.quad9.net
doh.cleanbrowsing.org
dns.nextdns.io
doh.pub
dot.pub
doh.alidns.com
dns.alidns.com
    """.trimIndent()

    /**
     * 统计 / 归因类域名，默认不启用：拦截会影响 App 的安装归因、分享统计等功能，
     * 对"屏蔽广告"没有直接帮助，仅在用户明确需要时手动添加。
     */
    val TRACKING_REFERENCE: String = """
app.adjust.com
app.appsflyer.com
api.kochava.com
singular.net
tracking.miui.com
    """.trimIndent()
}