# 反射只发生在 com.android.internal.os.PowerProfile（库类，R8 不裁剪/不改名），
# manifest 里的组件由 AGP 自动 keep，因此业务类这边不需要额外规则。

# 档案页的折线图是布局 XML 里按类名反射实例化的自定义 View，R8 看不到这个引用。
-keep class com.chargehud.app.ChargeChartView { <init>(...); }
