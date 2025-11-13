package app.pamoka.timetable

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class WeekDaysPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {

    // Show 100 weeks (500 days) - 50 weeks back and 50 weeks forward (Monday-Friday only)
    private val totalWeeks = 100
    override fun getItemCount(): Int = totalWeeks * 5 // 100 weeks * 5 days per week (Mon-Fri)

    override fun createFragment(position: Int): Fragment {
        // Convert position to day offset from current week's Monday
        // Position 0 = Monday, 50 weeks ago
        // Middle position = Current week

        val middlePosition = totalWeeks * 5 / 2
        val daysFromMiddle = position - middlePosition

        // Convert to actual day offset (Monday-Friday only)
        val weekOffset = daysFromMiddle / 5
        val dayInWeek = daysFromMiddle % 5

        // Monday=0, Tuesday=1, Wednesday=2, Thursday=3, Friday=4
        val dayOffset = dayInWeek

        // Calculate total day offset, skipping weekends
        // Each week has 7 days, but we only show 5 weekdays
        val totalDayOffset = (weekOffset * 7) + dayOffset

        return WeekDayFragment.newInstance(totalDayOffset)
    }
}