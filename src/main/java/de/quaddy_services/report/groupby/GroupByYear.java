/**
 * 
 */
package de.quaddy_services.report.groupby;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;

import de.quaddy_services.ptc.store.Task;

public class GroupByYear extends GroupBy {
	GroupByYear(String aName) {
		// protected
		super(aName);
	}

	private Calendar tempCal1 = Calendar.getInstance();
	private Calendar tempCal2 = Calendar.getInstance();

	@Override
	public int compare(Task t1, Task t2) {
		tempCal1.setTime(t1.getStart());
		tempCal2.setTime(t2.getStart());
		if (tempCal1.get(Calendar.YEAR) == tempCal2.get(Calendar.YEAR)) {
			return 0;
		}
		if (t1.getStart().getTime() < t2.getStart().getTime()) {
			return -1;
		}
		return 1;
	}

	private DateFormat DATE_FORMAT = new SimpleDateFormat("yyyy");

	@Override
	public String getGroupName(Task t) {
		return DATE_FORMAT.format(t.getStart());
	}
}