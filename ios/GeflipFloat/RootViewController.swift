import AVKit
import UIKit

/// The whole app: the page on top, a live preview of the floating window underneath, and
/// the two controls that matter. You only come back here to change the URL or restart it.
final class RootViewController: UIViewController {

	private let host = WebHost()
	private lazy var float = PipFloat(host: host)

	private let previewBox = UIView()
	private let statusLabel = UILabel()
	private let floatButton = UIButton(type: .system)

	private static let ink = UIColor(red: 0.902, green: 0.851, blue: 0.749, alpha: 1)
	private static let bg = UIColor(red: 0.078, green: 0.063, blue: 0.047, alpha: 1)
	private static let orange = UIColor(red: 1.0, green: 0.596, blue: 0.122, alpha: 1)
	private static let dim = UIColor(red: 0.604, green: 0.541, blue: 0.431, alpha: 1)

	override func viewDidLoad() {
		super.viewDidLoad()
		view.backgroundColor = Self.bg
		buildUI()

		float.onStatus = { [weak self] message in
			DispatchQueue.main.async { self?.statusLabel.text = message }
		}

		host.load(Prefs.url)
		float.attach(to: previewBox)
		float.begin()

		if !float.isSupported {
			statusLabel.text = "This device does not support Picture in Picture, so the "
				+ "window cannot float. The app above still works as the full geflip."
			floatButton.isEnabled = false
		} else {
			statusLabel.text = "Tap Float, then swipe to OSRS."
		}
	}

	override func viewDidLayoutSubviews() {
		super.viewDidLayoutSubviews()
		float.layout(in: previewBox.bounds)
	}

	// MARK: - UI

	private func buildUI() {
		let bar = UIStackView()
		bar.axis = .horizontal
		bar.spacing = 8
		bar.alignment = .center

		let title = UILabel()
		title.text = "GEFLIP"
		title.textColor = Self.orange
		title.font = .systemFont(ofSize: 14, weight: .bold)

		let settings = UIButton(type: .system)
		settings.setTitle("Page", for: .normal)
		settings.tintColor = Self.dim
		settings.addTarget(self, action: #selector(editURL), for: .touchUpInside)

		floatButton.setTitle("Float", for: .normal)
		floatButton.tintColor = Self.orange
		floatButton.titleLabel?.font = .systemFont(ofSize: 15, weight: .semibold)
		floatButton.addTarget(self, action: #selector(toggleFloat), for: .touchUpInside)

		bar.addArrangedSubview(title)
		bar.addArrangedSubview(UIView())
		bar.addArrangedSubview(settings)
		bar.addArrangedSubview(floatButton)

		statusLabel.textColor = Self.dim
		statusLabel.font = .systemFont(ofSize: 11.5)
		statusLabel.numberOfLines = 3

		previewBox.backgroundColor = .black
		previewBox.layer.cornerRadius = 8
		previewBox.clipsToBounds = true

		let web = host.webView
		for v in [bar, web, previewBox, statusLabel] as [UIView] {
			v.translatesAutoresizingMaskIntoConstraints = false
			view.addSubview(v)
		}

		let guide = view.safeAreaLayoutGuide
		NSLayoutConstraint.activate([
			bar.topAnchor.constraint(equalTo: guide.topAnchor, constant: 6),
			bar.leadingAnchor.constraint(equalTo: guide.leadingAnchor, constant: 12),
			bar.trailingAnchor.constraint(equalTo: guide.trailingAnchor, constant: -12),

			web.topAnchor.constraint(equalTo: bar.bottomAnchor, constant: 6),
			web.leadingAnchor.constraint(equalTo: view.leadingAnchor),
			web.trailingAnchor.constraint(equalTo: view.trailingAnchor),

			previewBox.topAnchor.constraint(equalTo: web.bottomAnchor, constant: 8),
			previewBox.leadingAnchor.constraint(equalTo: guide.leadingAnchor, constant: 12),
			previewBox.trailingAnchor.constraint(equalTo: guide.trailingAnchor, constant: -12),
			previewBox.heightAnchor.constraint(equalToConstant: 150),

			statusLabel.topAnchor.constraint(equalTo: previewBox.bottomAnchor, constant: 8),
			statusLabel.leadingAnchor.constraint(equalTo: guide.leadingAnchor, constant: 12),
			statusLabel.trailingAnchor.constraint(equalTo: guide.trailingAnchor, constant: -12),
			statusLabel.bottomAnchor.constraint(equalTo: guide.bottomAnchor, constant: -8),
		])
	}

	// MARK: - actions

	@objc private func toggleFloat() {
		if float.isActive {
			float.stopFloating()
		} else {
			float.startFloating()
		}
	}

	@objc private func editURL() {
		let sheet = UIAlertController(
			title: "Page",
			message: "Leave the default for live prices. Point it at the plugin bridge on "
				+ "your wifi — http://PC-IP:7777/?t=TOKEN — to also see your real fills and "
				+ "open GE offers.",
			preferredStyle: .alert)
		sheet.addTextField { field in
			field.text = Prefs.url
			field.placeholder = Prefs.defaultURL
			field.keyboardType = .URL
			field.autocorrectionType = .no
			field.autocapitalizationType = .none
		}
		sheet.addAction(UIAlertAction(title: "Cancel", style: .cancel))
		sheet.addAction(UIAlertAction(title: "Load", style: .default) { [weak self] _ in
			guard let self = self else { return }
			let typed = sheet.textFields?.first?.text ?? ""
			Prefs.url = typed.isEmpty ? Prefs.defaultURL : typed
			self.host.load(Prefs.url)
			self.statusLabel.text = "Loading \(Prefs.overlayURL(Prefs.url))"
		})
		present(sheet, animated: true)
	}
}
