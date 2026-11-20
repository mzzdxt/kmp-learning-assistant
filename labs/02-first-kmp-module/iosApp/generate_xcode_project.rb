#!/usr/bin/env ruby

gem "rexml", "= 3.2.9"
require "xcodeproj"

root = File.expand_path(__dir__)
project_path = File.join(root, "PocketLabIos.xcodeproj")
abort("#{project_path} already exists; delete it explicitly before regenerating") if File.exist?(project_path)

release_path = File.expand_path("../release.properties", root)
release_properties = File.readlines(release_path).each_with_object({}) do |line, properties|
  stripped = line.strip
  next if stripped.empty? || stripped.start_with?("#")

  key, value = stripped.split("=", 2)
  abort("Invalid release property: #{stripped}") if key.nil? || value.nil? || value.empty?
  properties[key] = value
end
release_version_name = release_properties.fetch("versionName")
release_build_number = release_properties.fetch("buildNumber")
abort("Invalid buildNumber: #{release_build_number}") unless release_build_number.match?(/\A\d+\z/)

project = Xcodeproj::Project.new(project_path)
target = project.new_target(:application, "PocketLabIos", :ios, "17.0")
ui_test_target = project.new_target(:ui_test_bundle, "PocketLabIosUITests", :ios, "17.0")

source_group = project.main_group.new_group("PocketLabIos", "PocketLabIos")
Dir[File.join(root, "PocketLabIos", "*.swift")].sort.each do |source|
  reference = source_group.new_file(File.basename(source))
  target.source_build_phase.add_file_reference(reference)
end

ui_test_group = project.main_group.new_group("PocketLabIosUITests", "PocketLabIosUITests")
Dir[File.join(root, "PocketLabIosUITests", "*.swift")].sort.each do |source|
  reference = ui_test_group.new_file(File.basename(source))
  ui_test_target.source_build_phase.add_file_reference(reference)
end
ui_test_target.add_dependency(target)

script = target.new_shell_script_build_phase("Compile Kotlin Framework")
script.shell_script = <<~SH
  if [ "YES" = "$OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED" ]; then
    echo "Skipping Gradle build because the KMP IDE already built the framework"
    exit 0
  fi
  cd "$SRCROOT/.."
  ./gradlew :shared:embedAndSignAppleFrameworkForXcode
SH
script.always_out_of_date = "1"
target.build_phases.delete(script)
target.build_phases.unshift(script)

project.build_configurations.each do |configuration|
  configuration.build_settings["SDKROOT"] = "iphoneos"
end

target.build_configurations.each do |configuration|
  settings = configuration.build_settings
  settings["PRODUCT_BUNDLE_IDENTIFIER"] = "dev.kmpjourney.pocketlab.ios"
  settings["PRODUCT_NAME"] = "PocketLabIos"
  settings["SWIFT_VERSION"] = "6.0"
  settings["IPHONEOS_DEPLOYMENT_TARGET"] = "17.0"
  settings["TARGETED_DEVICE_FAMILY"] = "1,2"
  settings["GENERATE_INFOPLIST_FILE"] = "YES"
  settings["INFOPLIST_KEY_CFBundleDisplayName"] = "PocketLab"
  settings["INFOPLIST_KEY_UIApplicationSceneManifest_Generation"] = "YES"
  settings["INFOPLIST_KEY_UILaunchScreen_Generation"] = "YES"
  settings["ENABLE_USER_SCRIPT_SANDBOXING"] = "NO"
  settings["FRAMEWORK_SEARCH_PATHS"] = "$(inherited) $(TARGET_BUILD_DIR)/$(FRAMEWORKS_FOLDER_PATH)"
  # PocketLabShared is a static framework. SQLDelight's Native driver calls the
  # platform sqlite3 API, so the final application target owns that system link.
  settings["OTHER_LDFLAGS"] = "$(inherited) -framework PocketLabShared -lsqlite3"
  settings["ONLY_ACTIVE_ARCH"] = configuration.name == "Debug" ? "YES" : "NO"
  settings["DEBUG_INFORMATION_FORMAT"] =
    configuration.name == "Debug" ? "dwarf" : "dwarf-with-dsym"
  settings["CURRENT_PROJECT_VERSION"] = release_build_number
  settings["MARKETING_VERSION"] = release_version_name
end

ui_test_target.build_configurations.each do |configuration|
  settings = configuration.build_settings
  settings["PRODUCT_BUNDLE_IDENTIFIER"] = "dev.kmpjourney.pocketlab.ios.uitests"
  settings["PRODUCT_NAME"] = "PocketLabIosUITests"
  settings["SWIFT_VERSION"] = "6.0"
  settings["IPHONEOS_DEPLOYMENT_TARGET"] = "17.0"
  settings["TARGETED_DEVICE_FAMILY"] = "1,2"
  settings["GENERATE_INFOPLIST_FILE"] = "YES"
  settings["TEST_TARGET_NAME"] = "PocketLabIos"
end

scheme = Xcodeproj::XCScheme.new
scheme.add_build_target(target)
scheme.add_test_target(ui_test_target)
scheme.set_launch_target(target)
scheme.save_as(project_path, "PocketLabIos", true)

project.save
puts "Generated #{project_path}"
