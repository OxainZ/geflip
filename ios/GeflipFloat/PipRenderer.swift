import CoreMedia
import CoreVideo
import UIKit

/// Paints one Picture-in-Picture frame.
///
/// This draws DATA, never decisions: every number here came out of `geflipSnapshot()`,
/// which is the page's own already-computed output. Nothing is scored, taxed or ranked
/// on this side. (The Android host can just show the page itself; iOS cannot draw one app
/// over another, and a PiP window is a video surface, so the frame has to be painted.)
final class PipRenderer {

	private enum C {
		static let bg     = UIColor(red: 0.078, green: 0.063, blue: 0.047, alpha: 1)
		static let line   = UIColor(red: 0.227, green: 0.184, blue: 0.141, alpha: 1)
		static let ink    = UIColor(red: 0.902, green: 0.851, blue: 0.749, alpha: 1)
		static let dim    = UIColor(red: 0.604, green: 0.541, blue: 0.431, alpha: 1)
		static let faint  = UIColor(red: 0.420, green: 0.365, blue: 0.275, alpha: 1)
		static let orange = UIColor(red: 1.000, green: 0.596, blue: 0.122, alpha: 1)
		static let gold   = UIColor(red: 1.000, green: 0.824, blue: 0.247, alpha: 1)
		static let green  = UIColor(red: 0.149, green: 0.878, blue: 0.478, alpha: 1)
		static let red    = UIColor(red: 1.000, green: 0.361, blue: 0.278, alpha: 1)
	}

	/// The design is laid out at this width and scaled to whatever PiP actually gives us,
	/// so the frame reads the same in a small window as a large one.
	private static let designWidth: CGFloat = 320

	private(set) var pixelSize = CGSize(width: 320, height: 400)

	func resize(to size: CGSize) {
		guard size.width > 16, size.height > 16 else { return }
		pixelSize = size
	}

	// MARK: - frame

	func makeFrame(snapshot: Snapshot?, note: String?, paused: Bool, now: Date = Date()) -> CVPixelBuffer? {
		let w = Int(pixelSize.width), h = Int(pixelSize.height)
		let attrs: [CFString: Any] = [
			kCVPixelBufferCGImageCompatibilityKey: true,
			kCVPixelBufferCGBitmapContextCompatibilityKey: true,
			// The display layer needs IOSurface-backed buffers; without this it drops frames.
			kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
		]
		var buffer: CVPixelBuffer?
		guard CVPixelBufferCreate(kCFAllocatorDefault, w, h, kCVPixelFormatType_32BGRA,
		                          attrs as CFDictionary, &buffer) == kCVReturnSuccess,
		      let pb = buffer
		else { return nil }

		CVPixelBufferLockBaseAddress(pb, [])
		defer { CVPixelBufferUnlockBaseAddress(pb, []) }

		guard let ctx = CGContext(
			data: CVPixelBufferGetBaseAddress(pb),
			width: w, height: h, bitsPerComponent: 8,
			bytesPerRow: CVPixelBufferGetBytesPerRow(pb),
			space: CGColorSpaceCreateDeviceRGB(),
			bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
		else { return nil }

		// CoreGraphics is bottom-up, UIKit text is top-down. Flip once, then think in UIKit.
		ctx.translateBy(x: 0, y: CGFloat(h))
		ctx.scaleBy(x: 1, y: -1)

		UIGraphicsPushContext(ctx)
		draw(into: ctx, snapshot: snapshot, note: note, paused: paused, now: now)
		UIGraphicsPopContext()
		return pb
	}

	private func draw(into ctx: CGContext, snapshot: Snapshot?, note: String?, paused: Bool, now: Date) {
		let scale = pixelSize.width / Self.designWidth
		let W = Self.designWidth
		let H = pixelSize.height / scale
		ctx.scaleBy(x: scale, y: scale)

		C.bg.setFill()
		ctx.fill(CGRect(x: 0, y: 0, width: W, height: H))

		let pad: CGFloat = 10
		var y: CGFloat = 8

		// ---- header: who we are, and how old this is ----
		text("GEFLIP", at: CGPoint(x: pad, y: y), font: .systemFont(ofSize: 12, weight: .bold),
		     color: C.orange, width: 90, kern: 1.6)

		let (ageText, ageColor) = age(of: snapshot, paused: paused, now: now)
		text(ageText, at: CGPoint(x: W - pad - 150, y: y + 1), font: mono(10), color: ageColor,
		     width: 150, align: .right)
		y += 20
		rule(ctx, y: y, width: W, pad: pad); y += 8

		guard let snap = snapshot, !snap.rows.isEmpty else {
			text(note ?? "No scan yet — open geflip and tap Scan.",
			     at: CGPoint(x: pad, y: y + 14), font: .systemFont(ofSize: 12), color: C.dim,
			     width: W - pad * 2, align: .center, lines: 3)
			return
		}

		// ---- your open GE offers, if the plugin is feeding the page ----
		if !snap.offers.isEmpty {
			label("YOUR GE", at: CGPoint(x: pad, y: y), width: W); y += 13
			for offer in snap.offers.prefix(3) {
				let mark = offer.stale ? "!" : (offer.buy ? "▼" : "▲")
				let head = offer.stale ? C.red : (offer.buy ? C.gold : C.green)
				text(mark, at: CGPoint(x: pad, y: y), font: mono(10), color: head, width: 12)
				text(offer.name, at: CGPoint(x: pad + 14, y: y), font: .systemFont(ofSize: 11.5, weight: .semibold),
				     color: offer.stale ? C.red : C.ink, width: W - pad * 2 - 100)
				let progress = offer.stale ? "reprice" : "\(offer.sold)/\(offer.total)"
				text("\(gp(offer.price))  \(progress)", at: CGPoint(x: W - pad - 100, y: y),
				     font: mono(10), color: offer.stale ? C.red : C.dim, width: 100, align: .right)
				y += 15
			}
			y += 4
			rule(ctx, y: y, width: W, pad: pad); y += 8
		}

		// ---- what to buy ----
		label(snap.allocated ? "BUY (YOUR SLOTS)" : "TOP BY GP/H", at: CGPoint(x: pad, y: y), width: W)
		y += 14

		let rowHeight: CGFloat = 32
		let room = max(0, Int((H - y - 6) / rowHeight))
		for row in snap.rows.prefix(min(4, room)) {
			text(row.name, at: CGPoint(x: pad, y: y), font: .systemFont(ofSize: 12.5, weight: .semibold),
			     color: C.ink, width: W - pad * 2 - 74)
			text("\(gp(row.gph))/h", at: CGPoint(x: W - pad - 74, y: y), font: mono(11),
			     color: C.green, width: 74, align: .right)
			y += 15
			let detail = "buy \(grouped(row.buy))   sell \(grouped(row.sell))   ×\(grouped(row.qty))"
			text(detail, at: CGPoint(x: pad, y: y), font: mono(10.5), color: C.gold, width: W - pad * 2)
			y += rowHeight - 15
		}

		if let note = note {
			text(note, at: CGPoint(x: pad, y: H - 18), font: mono(9.5), color: C.faint,
			     width: W - pad * 2, align: .center)
		}
	}

	// MARK: - bits

	private func age(of snap: Snapshot?, paused: Bool, now: Date) -> (String, UIColor) {
		guard let snap = snap, snap.t > 0 else { return ("no data", C.dim) }
		if snap.scanning { return ("scanning…", C.dim) }
		let seconds = Int(now.timeIntervalSince1970) - snap.t
		let ageText = seconds < 90 ? "\(max(0, seconds))s ago"
			: seconds < 5400 ? "\(seconds / 60)m ago"
			: "\(seconds / 3600)h ago"
		// Say it plainly when the numbers have gone off, rather than showing them as live.
		if seconds > 900 { return ("STALE · " + ageText, C.red) }
		if paused { return ("paused · " + ageText, C.dim) }
		return (ageText, C.dim)
	}

	private func mono(_ size: CGFloat) -> UIFont {
		UIFont.monospacedDigitSystemFont(ofSize: size, weight: .regular)
	}

	private func rule(_ ctx: CGContext, y: CGFloat, width: CGFloat, pad: CGFloat) {
		C.line.setFill()
		ctx.fill(CGRect(x: pad, y: y, width: width - pad * 2, height: 0.5))
	}

	private func label(_ s: String, at point: CGPoint, width: CGFloat) {
		text(s, at: point, font: .systemFont(ofSize: 9, weight: .medium), color: C.faint,
		     width: width, kern: 0.8)
	}

	private func text(_ s: String, at point: CGPoint, font: UIFont, color: UIColor,
	                  width: CGFloat, align: NSTextAlignment = .left, kern: CGFloat = 0,
	                  lines: Int = 1) {
		let para = NSMutableParagraphStyle()
		para.alignment = align
		para.lineBreakMode = lines > 1 ? .byWordWrapping : .byTruncatingTail
		let attrs: [NSAttributedString.Key: Any] = [
			.font: font, .foregroundColor: color, .paragraphStyle: para, .kern: kern,
		]
		let height = font.lineHeight * CGFloat(lines) + 2
		NSAttributedString(string: s, attributes: attrs)
			.draw(with: CGRect(x: point.x, y: point.y, width: width, height: height),
			      options: [.usesLineFragmentOrigin], context: nil)
	}

	private static let group: NumberFormatter = {
		let f = NumberFormatter()
		f.numberStyle = .decimal
		f.groupingSeparator = ","
		return f
	}()

	/// Exact, comma-grouped — this is a price you will type into the offer box.
	private func grouped(_ v: Int) -> String {
		Self.group.string(from: NSNumber(value: v)) ?? String(v)
	}

	/// Compact — this is a rate you only ever compare.
	private func gp(_ v: Int) -> String {
		let a = abs(v)
		let sign = v < 0 ? "-" : ""
		switch a {
		case 1_000_000_000...: return sign + String(format: "%.2fb", Double(a) / 1e9)
		case 1_000_000...:     return sign + String(format: "%.1fm", Double(a) / 1e6)
		case 100_000...:       return sign + "\(a / 1000)k"
		case 1_000...:         return sign + String(format: "%.1fk", Double(a) / 1e3)
		default:               return sign + String(a)
		}
	}
}
