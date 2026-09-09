import AVFoundation
import AVKit
import UIKit

/// The floating window.
///
/// iOS has no "draw over other apps" — there is no entitlement for it and no private door.
/// The only surface a third-party app can keep on screen while another app is in front is
/// Picture in Picture, so that is what this is: a live video stream whose every frame we
/// paint ourselves from the page's numbers.
///
/// Consequences worth knowing before reading further: a PiP window is a *video*, so it
/// cannot be tapped, scrolled or copied from. It shows; it does not interact.
final class PipFloat: NSObject {

	let displayLayer = AVSampleBufferDisplayLayer()

	/// Human-readable state for the setup screen — including the reason PiP refused, which
	/// is the only way to debug this on a device you cannot attach a debugger to.
	var onStatus: ((String) -> Void)?

	private let renderer = PipRenderer()
	private weak var host: WebHost?
	private var controller: AVPictureInPictureController?
	private var timer: Timer?
	private var lastScan = Date.distantPast
	private var paused = false
	private var note: String?

	private static let rescanInterval: TimeInterval = 180   // the wiki API asks for restraint

	init(host: WebHost) {
		self.host = host
		super.init()
		displayLayer.videoGravity = .resizeAspect
		displayLayer.backgroundColor = UIColor.black.cgColor
		paused = Prefs.paused
	}

	var isSupported: Bool { AVPictureInPictureController.isPictureInPictureSupported() }
	var isActive: Bool { controller?.isPictureInPictureActive ?? false }

	// MARK: - lifecycle

	/// Put the layer somewhere real. PiP will not adopt a layer that is not in a window,
	/// and showing it makes a useful preview of what will float.
	func attach(to view: UIView) {
		displayLayer.frame = view.bounds
		view.layer.addSublayer(displayLayer)
	}

	func layout(in bounds: CGRect) {
		displayLayer.frame = bounds
	}

	func begin() {
		guard isSupported else {
			onStatus?("This device does not support Picture in Picture.")
			return
		}
		// .mixWithOthers matters: without it, activating a playback session ducks or stops
		// the game's own audio, and an overlay that mutes OSRS is not worth having.
		do {
			let session = AVAudioSession.sharedInstance()
			try session.setCategory(.playback, mode: .moviePlayback, options: [.mixWithOthers])
			try session.setActive(true)
		} catch {
			onStatus?("Audio session refused (\(error.localizedDescription)) — PiP may not survive backgrounding.")
		}

		if controller == nil {
			let source = AVPictureInPictureController.ContentSource(
				sampleBufferDisplayLayer: displayLayer, playbackDelegate: self)
			let pip = AVPictureInPictureController(contentSource: source)
			pip.delegate = self
			// Float automatically when you swipe to the game — the whole point.
			pip.canStartPictureInPictureAutomaticallyFromInline = true
			controller = pip
		}

		startPump()
		pump()   // never hand PiP an empty layer; it wants a frame before it will start
	}

	func startFloating() {
		guard let pip = controller else {
			onStatus?("Not ready yet — give it a second.")
			return
		}
		guard pip.isPictureInPicturePossible else {
			onStatus?("iOS says PiP is not possible right now. It needs a frame on screen "
			        + "and the app in the foreground.")
			return
		}
		pip.startPictureInPicture()
	}

	func stopFloating() {
		controller?.stopPictureInPicture()
	}

	func end() {
		timer?.invalidate()
		timer = nil
		controller?.stopPictureInPicture()
		try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
	}

	// MARK: - frames

	private func startPump() {
		timer?.invalidate()
		let t = Timer(timeInterval: 1.0, repeats: true) { [weak self] _ in self?.pump() }
		// .common so the frames keep coming while a scroll view is tracking.
		RunLoop.main.add(t, forMode: .common)
		timer = t
	}

	private func pump() {
		guard let host = host else { return }

		if !paused, Date().timeIntervalSince(lastScan) > Self.rescanInterval {
			lastScan = Date()
			host.rescan()
		}

		host.snapshot { [weak self] snap in
			guard let self = self else { return }
			if snap == nil {
				// The page has not booted, or its JS is not answering. Say so on the frame
				// instead of quietly freezing on old numbers.
				self.note = host.lastError ?? "waiting for the page…"
			} else {
				self.note = self.paused ? "paused — press play to resume" : nil
			}
			guard let frame = self.renderer.makeFrame(snapshot: snap, note: self.note,
			                                          paused: self.paused) else { return }
			self.enqueue(frame)
		}
	}

	private func enqueue(_ pixels: CVPixelBuffer) {
		var format: CMFormatDescription?
		guard CMVideoFormatDescriptionCreateForImageBuffer(
			allocator: kCFAllocatorDefault, imageBuffer: pixels,
			formatDescriptionOut: &format) == noErr, let desc = format
		else { return }

		var timing = CMSampleTimingInfo(
			duration: .invalid,
			presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()),
			decodeTimeStamp: .invalid)

		var sample: CMSampleBuffer?
		guard CMSampleBufferCreateReadyWithImageBuffer(
			allocator: kCFAllocatorDefault, imageBuffer: pixels, formatDescription: desc,
			sampleTiming: &timing, sampleBufferOut: &sample) == noErr, let buffer = sample
		else { return }

		if displayLayer.status == .failed {
			displayLayer.flush()   // a failed layer stays failed until flushed
		}
		// enqueue(_:) is soft-deprecated in iOS 17 in favour of .sampleBufferRenderer, which
		// does not exist on 15/16. Keep this until the deployment floor moves.
		displayLayer.enqueue(buffer)
	}
}

// MARK: - PiP transport

extension PipFloat: AVPictureInPictureSampleBufferPlaybackDelegate {

	func pictureInPictureController(_ controller: AVPictureInPictureController, setPlaying playing: Bool) {
		paused = !playing
		Prefs.paused = paused
		if playing {
			lastScan = .distantPast   // resume means "get me fresh numbers now"
		}
		pump()
	}

	func pictureInPictureControllerTimeRangeForPlayback(_ controller: AVPictureInPictureController) -> CMTimeRange {
		// Live: no scrubber, no fake duration to seek around in.
		CMTimeRange(start: .negativeInfinity, duration: .positiveInfinity)
	}

	func pictureInPictureControllerIsPlaybackPaused(_ controller: AVPictureInPictureController) -> Bool {
		paused
	}

	func pictureInPictureController(_ controller: AVPictureInPictureController,
	                                didTransitionToRenderSize newRenderSize: CMVideoDimensions) {
		renderer.resize(to: CGSize(width: CGFloat(newRenderSize.width),
		                           height: CGFloat(newRenderSize.height)))
		pump()
	}

	func pictureInPictureController(_ controller: AVPictureInPictureController,
	                                skipByInterval skipInterval: CMTime,
	                                completion: @escaping () -> Void) {
		// There is nothing to seek in a live ticker, so spend the button on the thing you
		// would actually want from it: refresh now.
		lastScan = .distantPast
		pump()
		completion()
	}
}

// MARK: - PiP lifecycle

extension PipFloat: AVPictureInPictureControllerDelegate {

	func pictureInPictureController(_ controller: AVPictureInPictureController,
	                                failedToStartPictureInPictureWithError error: Error) {
		onStatus?("PiP refused to start: \(error.localizedDescription)")
	}

	func pictureInPictureControllerDidStartPictureInPicture(_ controller: AVPictureInPictureController) {
		onStatus?("Floating. Swipe to OSRS — it stays on top.")
	}

	func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) {
		onStatus?("Stopped floating.")
	}
}
