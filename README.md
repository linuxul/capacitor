<br />
<div align="center">
  <img src="https://user-images.githubusercontent.com/236501/105104854-e5e42e80-5a67-11eb-8cb8-46fccb079062.png" width="560" />
</div>
<div align="center">
  ⚡️ Cross-platform apps with JavaScript and the Web ⚡️
</div>
<br />
<p align="center">
  <a href="https://github.com/ionic-team/capacitor/actions?query=workflow%3ACI"><img src="https://img.shields.io/github/actions/workflow/status/ionic-team/capacitor/ci.yml?style=flat-square" /></a>
  <a href="https://www.npmjs.com/package/@capacitor/core"><img src="https://img.shields.io/npm/dw/@capacitor/core?style=flat-square" /></a>
  <a href="https://www.npmjs.com/package/@capacitor/core"><img src="https://img.shields.io/npm/v/@capacitor/core?style=flat-square" /></a>
  <a href="https://www.npmjs.com/package/@capacitor/core"><img src="https://img.shields.io/npm/l/@capacitor/core?style=flat-square" /></a>
</p>
<p align="center">
  <a href="https://capacitorjs.com/docs"><img src="https://img.shields.io/static/v1?label=docs&message=capacitorjs.com&color=blue&style=flat-square" /></a>
  <a href="https://twitter.com/capacitorjs"><img src="https://img.shields.io/twitter/follow/capacitorjs" /></a>
</p>

---

Capacitor lets you run web apps natively on iOS, Android, Web, and more with a single codebase and cross-platform APIs.

Capacitor provides a cross-platform API and code execution layer that makes it easy to call Native SDKs from web code and to write custom native plugins that your app may need. Additionally, Capacitor provides first-class Progressive Web App support so you can write one app and deploy it to the app stores _and_ the mobile web.

Capacitor comes with a Plugin API for building native plugins. Plugins can be written inside Capacitor apps or packaged into an npm dependency for community use. Plugin authors are encouraged to use Swift to develop plugins in iOS and Kotlin (or Java) in Android.

## Getting Started

Capacitor was designed to drop-in to any existing modern web app. Run the following commands to initialize Capacitor in your app:

```
npm install @capacitor/core @capacitor/cli
npx cap init
```

Next, install any of the desired native platforms:

```
npm install @capacitor/android
npx cap add android
npm install @capacitor/ios
npx cap add ios
```

### New App?

For new apps, we recommend trying the [Ionic Framework](https://ionicframework.com/) with Capacitor.

To begin, install the [Ionic CLI](https://ionicframework.com/docs/cli/) (`npm install -g @ionic/cli`) and start a new app:

```
ionic start --capacitor
```

## FAQ

#### How does this fork differ from upstream Capacitor?

This is a fork of Capacitor 8 whose native runtimes are written entirely in Kotlin (Android) and Swift (iOS). To get there it supports less than upstream does:

- iOS 17 and Android 13 (API 33) are the minimum versions.
- Plugins are Kotlin/Java on Android and Swift on iOS. Objective-C plugins are not supported.
- Cordova plugins are not supported.
- With Swift Package Manager, the iOS runtime is built from the source in `@capacitor/ios` rather than from the upstream binary release.

See [BREAKING.md](./BREAKING.md) for what an app or plugin written for upstream Capacitor has to change.

#### Do I need to use Ionic Framework with Capacitor?

No, you do not need to use Ionic Framework with Capacitor. Without the Ionic Framework, you may need to implement Native UI yourself. Without the Ionic CLI, you may need to configure tooling yourself to enable features such as [livereload](https://ionicframework.com/docs/cli/livereload). See [the docs](https://capacitorjs.com/docs/getting-started/with-ionic) for more details.

## Contributing

See [`CONTRIBUTING.md`](./CONTRIBUTING.md).

## Contributors

Made possible by the [Capacitor community](https://github.com/ionic-team/capacitor/graphs/contributors). 💖
