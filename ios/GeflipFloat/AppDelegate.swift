import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
	var window: UIWindow?

	func application(_ application: UIApplication,
	                 didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
		let w = UIWindow(frame: UIScreen.main.bounds)
		w.rootViewController = RootViewController()
		w.makeKeyAndVisible()
		window = w
		return true
	}
}
