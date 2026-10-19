package dev.repomind.eval;

import java.util.regex.Pattern;

/**
 * Recognises answers that decline to answer because the sources do not cover the question. A
 * phrase list is crude next to an LLM judge, but it is deterministic, free, and every verdict is
 * listed in the report so it can be audited by eye.
 */
public final class Abstention {

	private static final Pattern DECLINES = Pattern.compile(String.join("|",
			"\\b(?:do|does|did)(?:n't| not) (?:contain|include|mention|show|appear|provide|implement|use|have|define|reference|cover|describe)",
			"\\b(?:is|are)(?:n't| not) (?:mentioned|shown|present|included|implemented|covered|available|defined|found|referenced)",
			"\\bno (?:information|mention|reference|code|evidence|indication|implementation|details?)\\b",
			"\\b(?:could|can)(?:n't| ?not) (?:find|determine|answer|see|identify|locate)",
			"\\bnot (?:possible|able) to (?:determine|answer|tell)",
			"\\bthere (?:is|are) no\\b",
			"\\bnone of the (?:provided |given )?sources\\b",
			"\\bunable to (?:find|determine|answer|locate)"), Pattern.CASE_INSENSITIVE);

	private Abstention() {
	}

	public static boolean detect(String answer) {
		return answer != null && DECLINES.matcher(answer.replace('’', '\'')).find();
	}

}
