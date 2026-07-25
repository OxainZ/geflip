package com.geflip;

import java.util.List;

/**
 * Headless proof that the fetch + parse + scoring pipeline actually works against the
 * LIVE wiki API + geflip's trends.json — everything the plugin does EXCEPT the
 * RuneLite panel and offer tracking (those need the game). Run:  ./gradlew runCli
 */
public class GeflipCli
{
	public static void main(String[] args) throws Exception
	{
		GeflipConfig cfg = new GeflipConfig() {};   // all methods have defaults
		System.out.println("geflip CLI — scanning live wiki API…");
		List<GeflipScanner.Flip> flips = new GeflipScanner().scan(cfg);
		System.out.printf("top %d flips by gp/hour:%n", flips.size());
		System.out.printf("%-26s %10s %8s %8s %9s %7s%n", "item", "buy", "margin", "qty", "gp/h", "90d");
		for (GeflipScanner.Flip f : flips)
		{
			System.out.printf("%-26s %10d %8d %8d %9.0f %7s%s%n",
				f.name.length() > 26 ? f.name.substring(0, 26) : f.name,
				f.buy, f.margin, f.quantity, f.expGph,
				f.t90 == null ? "-" : String.format("%+.0f%%", f.t90 * 100),
				f.decliner ? "  <decliner>" : "");
		}
	}
}
