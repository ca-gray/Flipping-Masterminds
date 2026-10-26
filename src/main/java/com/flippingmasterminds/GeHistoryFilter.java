package com.flippingmasterminds;

import net.runelite.client.util.QuantityFormatter;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Search query for the GE history:
 *
 * <pre>
 *   whip            name contains "whip"
 *   >100m           total over 100m          (also &lt;, &gt;=, &lt;=, =)
 *   1m-5m           total between 1m and 5m
 *   each>1m         price per item over 1m   (also per / i / individual)
 *   whip >1m <3m    combine freely
 * </pre>
 */
class GeHistoryFilter
{
	private static final String NUMBER = "[0-9]+(?:\\.[0-9]+)?[kmb]?";
	private static final Pattern VALUE = Pattern.compile(
			"(?:(?<![\\w.])(?<mode>each|per|individual|iv|i)\\s*)?"
					+ "(?:(?<op>>=|<=|[<>=])\\s*(?<num>" + NUMBER + ")"
					+ "|(?<![\\w.])(?<num1>" + NUMBER + ")\\s*-\\s*(?<num2>" + NUMBER + "))(?![\\w.])",
			Pattern.CASE_INSENSITIVE);

	private static class Condition
	{
		boolean each;
		String op;
		long a;
		long b;

		boolean test(GeHistoryEntry e)
		{
			long v = each ? e.each : e.total;
			if (op == null) return v >= a && v <= b;
			switch (op)
			{
				case ">":  return v > a;
				case "<":  return v < a;
				case ">=": return v >= a;
				case "<=": return v <= a;
				default:   return v == a;
			}
		}
	}

	private final String name;
	private final List<Condition> conditions;

	private GeHistoryFilter(String name, List<Condition> conditions)
	{
		this.name       = name;
		this.conditions = conditions;
	}

	/** @return the parsed filter, or null if the query is blank */
	static GeHistoryFilter parse(String query)
	{
		if (query == null || query.trim().isEmpty()) return null;

		List<Condition> conditions = new ArrayList<>();
		StringBuffer rest = new StringBuffer();
		Matcher m = VALUE.matcher(query);
		while (m.find())
		{
			Condition c = new Condition();
			c.each = m.group("mode") != null;
			try
			{
				if (m.group("op") != null)
				{
					c.op = m.group("op");
					c.a  = QuantityFormatter.parseQuantity(m.group("num").toLowerCase(Locale.ROOT));
				}
				else
				{
					long x = QuantityFormatter.parseQuantity(m.group("num1").toLowerCase(Locale.ROOT));
					long y = QuantityFormatter.parseQuantity(m.group("num2").toLowerCase(Locale.ROOT));
					c.a = Math.min(x, y);
					c.b = Math.max(x, y);
				}
			}
			catch (ParseException e)
			{
				continue; // leave it in the name text
			}
			conditions.add(c);
			m.appendReplacement(rest, " ");
		}
		m.appendTail(rest);

		String name = rest.toString().trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
		return new GeHistoryFilter(name, conditions);
	}

	boolean matches(GeHistoryEntry entry, String itemName)
	{
		if (!name.isEmpty()
				&& (itemName == null || !itemName.toLowerCase(Locale.ROOT).contains(name)))
		{
			return false;
		}
		for (Condition c : conditions)
		{
			if (!c.test(entry)) return false;
		}
		return true;
	}
}
