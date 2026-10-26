package com.flippingmasterminds;

import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Helpers for game widget text, which mixes plain text with {@code <col=...>}-style tags. */
final class TaggedText
{
	private TaggedText()
	{
	}

	/**
	 * Replace matches of {@code pattern} in the text outside tags. The function gets the match
	 * and the raw text following it (tags included), and returns the replacement.
	 */
	static String replace(String raw, Pattern pattern, BiFunction<Matcher, String, String> fn)
	{
		StringBuilder out = new StringBuilder();
		int i = 0;
		while (i < raw.length())
		{
			int tag = raw.indexOf('<', i);
			if (tag == i)
			{
				int close = raw.indexOf('>', i);
				if (close < 0) close = raw.length() - 1;
				out.append(raw, i, close + 1);
				i = close + 1;
				continue;
			}
			int end = tag < 0 ? raw.length() : tag;
			Matcher m = pattern.matcher(raw.substring(i, end));
			StringBuffer sb = new StringBuffer();
			while (m.find())
			{
				String after = raw.substring(i + m.end());
				m.appendReplacement(sb, Matcher.quoteReplacement(fn.apply(m, after)));
			}
			m.appendTail(sb);
			out.append(sb);
			i = end;
		}
		return out.toString();
	}
}
