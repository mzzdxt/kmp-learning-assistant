# Publishing example only. Upload the matching archive before publishing this spec.
Pod::Spec.new do |spec|
  spec.name = "PocketLabShared"
  spec.version = "0.17.0"
  spec.summary = "PocketLab shared Kotlin Multiplatform business core"
  spec.homepage = "https://github.com/mzzdxt/kmp-learning-assistant"
  spec.license = { :type => "Proprietary" }
  spec.author = { "PocketLab" => "pocketlab@example.invalid" }
  spec.source = {
    :http => "https://github.com/mzzdxt/kmp-learning-assistant/releases/download/apple-0.17.0/PocketLabShared-0.17.0.xcframework.zip"
  }
  spec.ios.deployment_target = "15.0"
  spec.ios.vendored_frameworks = "PocketLabShared.xcframework"
  spec.static_framework = true
end
