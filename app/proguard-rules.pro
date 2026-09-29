# 反射只发生在 com.android.internal.os.PowerProfile（库类，R8 不裁剪/不改名），
# 四个组件都由 AndroidManifest 自动 keep，因此这里不需要额外的 keep 规则。
