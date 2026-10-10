import Foundation

/// Where the page lives, and what the window remembered.
enum Prefs {
	static let defaultURL = "https://oxainz.github.io/geflip/"

	private static let kURL = "geflip.url"
	private static let kPaused = "geflip.paused"

	static var url: String {
		get { UserDefaults.standard.string(forKey: kURL) ?? defaultURL }
		set { UserDefaults.standard.set(newValue, forKey: kURL) }
	}

	static var paused: Bool {
		get { UserDefaults.standard.bool(forKey: kPaused) }
		set { UserDefaults.standard.set(newValue, forKey: kPaused) }
	}

	/// Turn whatever the user typed into the URL to actually open: adds a scheme if they
	/// left it off, keeps any query they pasted (the LAN bridge needs its `?t=` token) and
	/// adds `overlay=1` exactly once. Mirrors `Prefs.overlayUrl` in android/.
	static func overlayURL(_ raw: String) -> String {
		var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
		if s.isEmpty { return defaultURL + "?overlay=1" }

		if !s.contains("://") {
			let lan = ["192.168.", "10.", "172.", "127.", "localhost"].contains { s.hasPrefix($0) }
			s = (lan ? "http://" : "https://") + s
		}

		// A fragment has to stay last, so peel it off and put it back.
		var frag = ""
		if let hash = s.firstIndex(of: "#") {
			frag = String(s[hash...])
			s = String(s[s.startIndex..<hash])
		}

		if let q = s.firstIndex(of: "?") {
			let query = s[s.index(after: q)...]
			for part in query.split(separator: "&") where part == "overlay=1" {
				return s + frag
			}
			return s + "&overlay=1" + frag
		}
		return s + "?overlay=1" + frag
	}
}
