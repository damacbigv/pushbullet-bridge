package com.example.pushbridge.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650A4)
val PurpleGrey40 = Color(0xFF625B71)
val Pink40 = Color(0xFF7D5260)

val EmeraldGreen = Color(0xFF10B981)
val RoseAlert = Color(0xFFF43F5E)
val AmberWarn = Color(0xFFF59E0B)
val GroupPurple = Color(0xFF8B5CF6)

data class PushBridgePalette(
    val id: String,
    val label: String,
    val isLight: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceSubtle: Color,
    val surfaceHover: Color,
    val border: Color,
    val textMain: Color,
    val textMuted: Color,
    val textSubtle: Color,
    val primary: Color,
    val onPrimary: Color,
    val accent: Color,
    val msgSent: Color,
    val msgSentText: Color,
    val msgRecv: Color,
    val msgRecvText: Color,
    val activeItem: Color,
    val badgeBg: Color,
    val badgeText: Color
)

object PushBridgePalettes {
    val Midnight = PushBridgePalette(
        id = "midnight",
        label = "Midnight Dark",
        isLight = false,
        background = Color(0xFF090D16),
        surface = Color(0xFF0F172A),
        surfaceSubtle = Color(0xFF0B1120),
        surfaceHover = Color(0xFF1E293B),
        border = Color(0xFF1E293B),
        textMain = Color(0xFFF8FAFC),
        textMuted = Color(0xFF94A3B8),
        textSubtle = Color(0xFF64748B),
        primary = Color(0xFF0284C7),
        onPrimary = Color.White,
        accent = Color(0xFF38BDF8),
        msgSent = Color(0xFF0284C7),
        msgSentText = Color.White,
        msgRecv = Color(0xFF1E293B),
        msgRecvText = Color(0xFFF8FAFC),
        activeItem = Color(0xFF1E293B),
        badgeBg = Color(0x260284C7),
        badgeText = Color(0xFF38BDF8)
    )

    val Solarized = PushBridgePalette(
        id = "solarized",
        label = "Solarized Clean",
        isLight = true,
        background = Color(0xFFFDF6E3),
        surface = Color(0xFFEEE8D5),
        surfaceSubtle = Color(0xFFF7F1DF),
        surfaceHover = Color(0xFFE2DBC8),
        border = Color(0xFFD5CDB7),
        textMain = Color(0xFF073642),
        textMuted = Color(0xFF586E75),
        textSubtle = Color(0xFF657B83),
        primary = Color(0xFF268BD2),
        onPrimary = Color.White,
        accent = Color(0xFF2AA198),
        msgSent = Color(0xFF268BD2),
        msgSentText = Color.White,
        msgRecv = Color(0xFFE2DBC8),
        msgRecvText = Color(0xFF073642),
        activeItem = Color(0xFFDFD8C2),
        badgeBg = Color(0x26268BD2),
        badgeText = Color(0xFF17669D)
    )

    val Emerald = PushBridgePalette(
        id = "emerald",
        label = "Emerald Forest",
        isLight = false,
        background = Color(0xFF04140E),
        surface = Color(0xFF0A2E20),
        surfaceSubtle = Color(0xFF061C13),
        surfaceHover = Color(0xFF114232),
        border = Color(0xFF134E38),
        textMain = Color(0xFFF0FDF4),
        textMuted = Color(0xFF86EFAC),
        textSubtle = Color(0xFF4ADE80),
        primary = Color(0xFF059669),
        onPrimary = Color.White,
        accent = Color(0xFF10B981),
        msgSent = Color(0xFF047857),
        msgSentText = Color.White,
        msgRecv = Color(0xFF114232),
        msgRecvText = Color(0xFFF0FDF4),
        activeItem = Color(0xFF134E38),
        badgeBg = Color(0x2E10B981),
        badgeText = Color(0xFF6EE7B7)
    )

    val Sunset = PushBridgePalette(
        id = "sunset",
        label = "Sunset Neon",
        isLight = false,
        background = Color(0xFF12091F),
        surface = Color(0xFF1E1035),
        surfaceSubtle = Color(0xFF170B2C),
        surfaceHover = Color(0xFF291546),
        border = Color(0xFF3C1E67),
        textMain = Color(0xFFFAF5FF),
        textMuted = Color(0xFFD8B4FE),
        textSubtle = Color(0xFFC084FC),
        primary = Color(0xFFDB2777),
        onPrimary = Color.White,
        accent = Color(0xFFF43F5E),
        msgSent = Color(0xFFBE185D),
        msgSentText = Color.White,
        msgRecv = Color(0xFF331956),
        msgRecvText = Color(0xFFFAF5FF),
        activeItem = Color(0xFF3C1E67),
        badgeBg = Color(0x2EEC4899),
        badgeText = Color(0xFFF472B6)
    )

    val Minimalist = PushBridgePalette(
        id = "minimalist",
        label = "Minimalist",
        isLight = false,
        background = Color(0xFF0D0D0D),
        surface = Color(0xFF171717),
        surfaceSubtle = Color(0xFF121212),
        surfaceHover = Color(0xFF212121),
        border = Color(0xFF2A2A2A),
        textMain = Color(0xFFF5F5F5),
        textMuted = Color(0xFFA3A3A3),
        textSubtle = Color(0xFF737373),
        primary = Color(0xFF3B82F6),
        onPrimary = Color.White,
        accent = Color(0xFF60A5FA),
        msgSent = Color(0xFF262626),
        msgSentText = Color.White,
        msgRecv = Color(0xFF1C1C1C),
        msgRecvText = Color(0xFFF5F5F5),
        activeItem = Color(0xFF282828),
        badgeBg = Color(0x1FFFFFFF),
        badgeText = Color(0xFFE5E5E5)
    )

    val Cyberpunk = PushBridgePalette(
        id = "cyberpunk",
        label = "Cyberpunk Neon",
        isLight = false,
        background = Color(0xFF050508),
        surface = Color(0xFF0E0E18),
        surfaceSubtle = Color(0xFF090912),
        surfaceHover = Color(0xFF18182B),
        border = Color(0xFF22223B),
        textMain = Color(0xFFFFFFFF),
        textMuted = Color(0xFF00F0FF),
        textSubtle = Color(0xFFA5B4FC),
        primary = Color(0xFFFFE600),
        onPrimary = Color(0xFF050508),
        accent = Color(0xFF00F0FF),
        msgSent = Color(0xFFFF0055),
        msgSentText = Color.White,
        msgRecv = Color(0xFF101026),
        msgRecvText = Color.White,
        activeItem = Color(0xFF181830),
        badgeBg = Color(0x2E00F0FF),
        badgeText = Color(0xFF00F0FF)
    )

    val Indigo = PushBridgePalette(
        id = "indigo",
        label = "Electric Indigo",
        isLight = false,
        background = Color(0xFF08081A),
        surface = Color(0xFF101030),
        surfaceSubtle = Color(0xFF0C0C24),
        surfaceHover = Color(0xFF1C1C4D),
        border = Color(0xFF2A2B6E),
        textMain = Color(0xFFF5F3FF),
        textMuted = Color(0xFFA5B4FC),
        textSubtle = Color(0xFF818CF8),
        primary = Color(0xFF6366F1),
        onPrimary = Color.White,
        accent = Color(0xFF818CF8),
        msgSent = Color(0xFF4F46E5),
        msgSentText = Color.White,
        msgRecv = Color(0xFF191942),
        msgRecvText = Color(0xFFF5F3FF),
        activeItem = Color(0xFF212154),
        badgeBg = Color(0x2E6366F1),
        badgeText = Color(0xFFA5B4FC)
    )

    val EmeraldSunset = PushBridgePalette(
        id = "emeraldsunset",
        label = "Emerald Sunset",
        isLight = false,
        background = Color(0xFF06130E),
        surface = Color(0xFF0B241B),
        surfaceSubtle = Color(0xFF081A14),
        surfaceHover = Color(0xFF113629),
        border = Color(0xFF1B4D3E),
        textMain = Color(0xFFFFFBEB),
        textMuted = Color(0xFFFCD34D),
        textSubtle = Color(0xFFFBBF24),
        primary = Color(0xFFF59E0B),
        onPrimary = Color(0xFF06130E),
        accent = Color(0xFF10B981),
        msgSent = Color(0xFFD97706),
        msgSentText = Color.White,
        msgRecv = Color(0xFF0E3024),
        msgRecvText = Color(0xFFFFFBEB),
        activeItem = Color(0xFF154232),
        badgeBg = Color(0x2EF59E0B),
        badgeText = Color(0xFFFCD34D)
    )

    val Crimson = PushBridgePalette(
        id = "crimson",
        label = "Crimson Velvet",
        isLight = false,
        background = Color(0xFF150508),
        surface = Color(0xFF240A10),
        surfaceSubtle = Color(0xFF1A070C),
        surfaceHover = Color(0xFF38101A),
        border = Color(0xFF4A121E),
        textMain = Color(0xFFFFF1F2),
        textMuted = Color(0xFFFDA4AF),
        textSubtle = Color(0xFFFB7185),
        primary = Color(0xFFE11D48),
        onPrimary = Color.White,
        accent = Color(0xFFF43F5E),
        msgSent = Color(0xFF9F1239),
        msgSentText = Color.White,
        msgRecv = Color(0xFF330D17),
        msgRecvText = Color(0xFFFFF1F2),
        activeItem = Color(0xFF3D101C),
        badgeBg = Color(0x2EE11D48),
        badgeText = Color(0xFFFDA4AF)
    )

    val Slate = PushBridgePalette(
        id = "slate",
        label = "Modern Slate",
        isLight = false,
        background = Color(0xFF0F141C),
        surface = Color(0xFF18202C),
        surfaceSubtle = Color(0xFF131922),
        surfaceHover = Color(0xFF222D3D),
        border = Color(0xFF2A384C),
        textMain = Color(0xFFF8FAFC),
        textMuted = Color(0xFF94A3B8),
        textSubtle = Color(0xFF64748B),
        primary = Color(0xFF38BDF8),
        onPrimary = Color(0xFF0F141C),
        accent = Color(0xFF0EA5E9),
        msgSent = Color(0xFF1E3A5F),
        msgSentText = Color.White,
        msgRecv = Color(0xFF1C2635),
        msgRecvText = Color(0xFFF8FAFC),
        activeItem = Color(0xFF253347),
        badgeBg = Color(0x2638BDF8),
        badgeText = Color(0xFF38BDF8)
    )

    val all: List<PushBridgePalette> = listOf(
        Midnight,
        Solarized,
        Emerald,
        Sunset,
        Minimalist,
        Cyberpunk,
        Indigo,
        EmeraldSunset,
        Crimson,
        Slate
    )

    fun byId(id: String?): PushBridgePalette {
        return all.firstOrNull { it.id == id } ?: Midnight
    }
}
