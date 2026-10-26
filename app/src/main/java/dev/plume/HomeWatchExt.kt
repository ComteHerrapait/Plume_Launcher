package dev.plume

/**
 * Upstream [HomeWatch] plus the fork's Google TV guard.
 *
 * Upstream covers any Google TV launcher window that is not the dashboard. The fork only covers the
 * stock home screen, so the setup and onboarding screens stay visible.
 */
class HomeWatchExt {
    private val watch = HomeWatch()

    fun opened(pkg: String, cls: String?, now: Long): Long? {
        if (pkg == HomeWatch.GOOGLE_TV && cls?.contains("HomeActivity", ignoreCase = true) != true) return null
        return watch.opened(pkg, cls, now)
    }
}
