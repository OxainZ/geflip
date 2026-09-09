import Foundation
import WebKit

/// The `window.geflipSnapshot()` payload. Mirrors the STABLE API block in index.html —
/// change one without the other and the window goes blank.
struct Snapshot: Decodable {
	struct Row: Decodable {
		let name: String
		let buy: Int
		let sell: Int
		let qty: Int
		let gph: Int
	}
	struct Offer: Decodable {
		let name: String
		let price: Int
		let sold: Int
		let total: Int
		let buy: Bool
		let stale: Bool
	}
	let v: Int
	let t: Int
	let allocated: Bool
	let scanning: Bool
	let rows: [Row]
	let offers: [Offer]
	let realized: Int
}

/// Holds the page. It is the entire flip engine — this app never scores an item, it asks.
final class WebHost: NSObject, WKNavigationDelegate {
	let webView: WKWebView
	private(set) var lastError: String?

	override init() {
		let cfg = WKWebViewConfiguration()
		cfg.allowsInlineMediaPlayback = true
		cfg.mediaTypesRequiringUserActionForPlayback = .all
		webView = WKWebView(frame: .zero, configuration: cfg)
		super.init()
		webView.navigationDelegate = self
		webView.isOpaque = false
		webView.backgroundColor = UIColor(red: 0.078, green: 0.063, blue: 0.047, alpha: 1)
		webView.scrollView.backgroundColor = webView.backgroundColor
	}

	func load(_ raw: String) {
		lastError = nil
		guard let url = URL(string: Prefs.overlayURL(raw)) else {
			lastError = "That is not a URL"
			return
		}
		webView.load(URLRequest(url: url))
	}

	/// Ask the page to rescan. Native has to drive this: once we are behind the game the
	/// page's own timer is throttled, and a window showing 40-minute-old prices is worse
	/// than no window.
	func rescan() {
		webView.evaluateJavaScript("typeof ovScanNow==='function' && (ovScanNow(), 1)")
	}

	/// Read the numbers the page already computed.
	func snapshot(_ done: @escaping (Snapshot?) -> Void) {
		let js = "typeof window.geflipSnapshot==='function' ? JSON.stringify(window.geflipSnapshot()) : ''"
		webView.evaluateJavaScript(js) { value, _ in
			guard let s = value as? String, !s.isEmpty, let data = s.data(using: .utf8),
			      let snap = try? JSONDecoder().decode(Snapshot.self, from: data)
			else {
				done(nil)
				return
			}
			done(snap)
		}
	}

	func webView(_ webView: WKWebView, didFailProvisionalNavigation nav: WKNavigation!, withError error: Error) {
		lastError = error.localizedDescription
	}

	func webView(_ webView: WKWebView, didFail nav: WKNavigation!, withError error: Error) {
		lastError = error.localizedDescription
	}

	func webView(_ webView: WKWebView, didFinish nav: WKNavigation!) {
		lastError = nil
	}
}
