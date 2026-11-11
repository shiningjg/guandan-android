# 保留应用入口与核心领域层（防止 R8 误删被间接引用的类）
-keep public class com.example.guandan.MainActivity
-keep class com.example.guandan.domain.** { *; }

# Compose / AndroidX 自带 keep 规则由 AGP 注入，这里不做激进裁剪
