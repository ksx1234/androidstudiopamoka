package app.pamoka.timetable

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

class WeekDayFragment : Fragment() {

    companion object {
        private const val ARG_DAY_OFFSET = "day_offset"

        fun newInstance(dayOffset: Int): WeekDayFragment {
            val fragment = WeekDayFragment()
            val args = Bundle()
            args.putInt(ARG_DAY_OFFSET, dayOffset)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // You can keep this empty or add other content to the page
        // The date information is now shown in the toolbar
        return inflater.inflate(R.layout.fragment_weekday, container, false)
    }
}