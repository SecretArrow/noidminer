package org.secretarrow.noidminer.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// NoidMiner v2 — "Aurora Midnight" palette
// Deep oceanic dark base + electric violet/mint/cyan + amber accent.
// Inspired by Linear.app dark + Vercel geist + Aurora Borealis night sky.
// ============================================================================

// --- Dark base (signature) ---
val MidnightBase = Color(0xFF070912) // near-black with blue tint
val MidnightSurface = Color(0xFF0E1320) // card background
val MidnightSurfaceHigh = Color(0xFF161D2E) // elevated card / pressed
val MidnightOutline = Color(0xFF25304A) // subtle dividers
val MidnightGlass = Color(0xCC141A2E) // 80% alpha for glass cards

// --- Light base ---
val DayBase = Color(0xFFF5F7FC)
val DaySurface = Color(0xFFFFFFFF)
val DaySurfaceHigh = Color(0xFFECEFF7)
val DayOutline = Color(0xFFD6DCEC)
val DayTextPrimary = Color(0xFF10141F)
val DayTextSecondary = Color(0xFF5A6478)

// --- Aurora accents (signature gradient stops) ---
val AuroraViolet = Color(0xFF8B5CF6) // primary
val AuroraVioletDim = Color(0xFF5B3FAE) // primary pressed / dark
val AuroraCyan = Color(0xFF22D3EE) // secondary
val AuroraMint = Color(0xFF34D399) // tertiary (success / mining)
val AuroraAmber = Color(0xFFFBBF24) // warning (throttle)
val AuroraRose = Color(0xFFFB7185) // error / stop
val AuroraPink = Color(0xFFF472B6) // accent pink for special highlights

// --- Text colors (dark) ---
val TextPrimary = Color(0xFFEFF3FB)
val TextSecondary = Color(0xFF8B95B0)
val TextTertiary = Color(0xFF5E6A85)

// --- Gradients ---

/** Hero gradient — violet → cyan, signature look across the app. */
val GradientHero = listOf(AuroraViolet, AuroraCyan)

/** Stop / danger gradient — rose → amber. */
val GradientDanger = listOf(AuroraRose, AuroraAmber)

/** Success / mining gradient — mint → cyan. */
val GradientMining = listOf(AuroraMint, AuroraCyan)

/** Idle gradient — surface high only, low-emphasis. */
val GradientIdle = listOf(MidnightSurfaceHigh, MidnightSurfaceHigh)
