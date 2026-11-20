#!/usr/bin/env ruby

require "date"
require "json"

class ContractVerifier
  REQUIRED_OWNER_AREAS = %w[
    release_command
    shared_runtime
    android_app
    ios_app
    data_migration
    observability
  ].freeze
  ALLOWED_GATE_STATES = %w[passed blocked not_implemented].freeze
  REQUIRED_METRIC_SLICES = %w[platform version build cohort].freeze
  IOS_PHASES = [1, 2, 5, 10, 20, 50, 100].freeze

  attr_reader :errors

  def initialize(contract, project_root:, check_repository: true)
    @contract = contract
    @project_root = project_root
    @check_repository = check_repository
    @errors = []
  end

  def verify
    verify_top_level
    verify_dates
    verify_release_identity
    verify_owners
    verify_compatibility
    verify_metrics
    verify_rollout
    verify_feature_toggles
    verify_rollback
    verify_artifacts
    verify_gates_and_readiness
    verify_repository if @check_repository
    errors
  end

  private

  def verify_top_level
    required = %w[
      schema_version product generated_on review_by release_identity owners
      compatibility promotion_metrics rollout feature_toggles rollback artifacts
      gates readiness
    ]
    missing = required.reject { |key| @contract.key?(key) }
    add("missing top-level keys: #{missing.join(', ')}") unless missing.empty?
    add("schema_version must be 1") unless @contract["schema_version"] == 1
    add("product must be PocketLab") unless @contract["product"] == "PocketLab"
  end

  def verify_dates
    generated = parse_date(@contract["generated_on"], "generated_on")
    review = parse_date(@contract["review_by"], "review_by")
    return if generated.nil? || review.nil?

    window = (review - generated).to_i
    add("review window must be between 1 and 180 days") unless window.between?(1, 180)
  end

  def verify_release_identity
    identity = object(@contract["release_identity"], "release_identity")
    required = %w[source version_name build_number database_schema diagnostic_schema immutable_artifacts]
    missing = required.reject { |key| identity.key?(key) }
    add("release_identity missing: #{missing.join(', ')}") unless missing.empty?
    add("version_name must use semantic x.y.z form") unless identity["version_name"].to_s.match?(/\A\d+\.\d+\.\d+\z/)
    add("build_number must be a positive integer") unless positive_integer?(identity["build_number"])
    add("database_schema must be a positive integer") unless positive_integer?(identity["database_schema"])
    add("diagnostic_schema must be a positive integer") unless positive_integer?(identity["diagnostic_schema"])
    add("release artifacts must be immutable") unless identity["immutable_artifacts"] == true
  end

  def verify_owners
    owners = array(@contract["owners"], "owners")
    areas = owners.map { |owner| owner["area"] }
    add("owner areas must be unique") unless areas.compact.uniq.length == areas.compact.length

    missing = REQUIRED_OWNER_AREAS - areas
    add("missing owner areas: #{missing.join(', ')}") unless missing.empty?

    owners.each do |owner|
      area = owner["area"] || "unknown"
      primary = owner["primary"].to_s
      backup = owner["backup"].to_s
      add("#{area} needs a primary owner") if primary.empty?
      add("#{area} needs a backup owner") if backup.empty?
      add("#{area} primary and backup must differ") if !primary.empty? && primary == backup
      add("#{area} cannot be owned by everyone") if [primary, backup].any? { |value| value.downcase == "everyone" }
    end
  end

  def verify_compatibility
    compatibility = object(@contract["compatibility"], "compatibility")
    mixed = object(compatibility["mixed_version_window"], "compatibility.mixed_version_window")
    database = object(compatibility["database"], "compatibility.database")
    binary = object(compatibility["shared_binary"], "compatibility.shared_binary")
    identity = object(@contract["release_identity"], "release_identity")

    add("mixed-version max_build_gap must be positive") unless positive_integer?(mixed["max_build_gap"])
    add("server contract must be additive") unless mixed["server_contract"] == "additive"
    add("unknown fields must be ignored during the mixed-version window") unless mixed["unknown_fields"] == "ignore"
    add("database migration strategy must be expand_migrate_contract") unless database["migration_strategy"] == "expand_migrate_contract"
    add("database rollback must use a forward fix") unless database["rollback_mode"] == "forward_fix_only"
    add("destructive down migrations are forbidden") unless database["destructive_down_migration"] == false
    add("database must have one active writer") unless database["single_writer"] == true
    add("database schema disagrees with release identity") unless database["current_schema"] == identity["database_schema"]
    add("shared binary version disagrees with release identity") unless binary["version_name"] == identity["version_name"]
    add("shared binary build disagrees with release identity") unless binary["build_number"] == identity["build_number"]
    add("shared binary coordinates must be immutable") unless binary["coordinates_are_immutable"] == true
  end

  def verify_metrics
    metrics = array(@contract["promotion_metrics"], "promotion_metrics")
    ids = metrics.map { |metric| metric["id"] }
    add("promotion metric ids must be unique") unless ids.compact.uniq.length == ids.compact.length
    add("promotion metrics cannot be empty") if metrics.empty?

    metrics.each do |metric|
      id = metric["id"] || "unknown"
      comparison = metric["comparison"].to_s
      add("#{id} needs an approved comparison rule") if comparison.empty?
      slices = array(metric["slices"], "promotion metric #{id} slices")
      missing = REQUIRED_METRIC_SLICES - slices
      add("#{id} missing metric slices: #{missing.join(', ')}") unless missing.empty?
    end
  end

  def verify_rollout
    rollout = object(@contract["rollout"], "rollout")
    add("rollout selection must be stable for an app session") unless rollout["selection_scope"] == "app_session"
    add("internal gate must precede store rollout") unless rollout["internal_gate_required"] == true

    verify_stages("android", object(rollout["android"], "rollout.android"), nil)
    verify_stages("ios", object(rollout["ios"], "rollout.ios"), IOS_PHASES)

    android = object(rollout["android"], "rollout.android")
    ios = object(rollout["ios"], "rollout.ios")
    add("Android halt must acknowledge already-updated users") unless android["already_updated_users_remain_on_new_build"] == true
    add("iOS phased release must acknowledge manual downloads") unless ios["manual_downloads_can_bypass_phase"] == true
  end

  def verify_stages(platform, config, expected_percentages)
    stages = array(config["stages"], "#{platform} rollout stages")
    percentages = stages.map { |stage| stage["percent"] }
    valid_numbers = percentages.all? { |value| value.is_a?(Integer) && value.between?(1, 100) }
    add("#{platform} rollout percentages must be integers from 1 to 100") unless valid_numbers
    add("#{platform} rollout percentages must strictly increase") unless percentages.each_cons(2).all? { |left, right| left < right }
    add("#{platform} rollout must end at 100 percent") unless percentages.last == 100
    if expected_percentages && percentages != expected_percentages
      add("#{platform} rollout phases must match #{expected_percentages.join(',')}")
    end
    stages.each do |stage|
      add("#{platform} #{stage['percent']}% stage needs a positive observation window") unless positive_integer?(stage["minimum_observation_hours"])
    end
  end

  def verify_feature_toggles
    toggles = array(@contract["feature_toggles"], "feature_toggles")
    ids = toggles.map { |toggle| toggle["id"] }
    add("feature toggle ids must be unique") unless ids.compact.uniq.length == ids.compact.length
    owner_areas = array(@contract["owners"], "owners").map { |owner| owner["area"] }
    review = parse_date(@contract["review_by"], "review_by")

    toggles.each do |toggle|
      id = toggle["id"] || "unknown"
      add("#{id} must default off before rollout evidence") unless toggle["default"] == false
      add("#{id} selection scope must be app_session") unless toggle["selection_scope"] == "app_session"
      add("#{id} owner area is unknown") unless owner_areas.include?(toggle["owner_area"])
      add("#{id} needs a removal condition") if toggle["removal_condition"].to_s.empty?
      add("#{id} cannot introduce two data writers") unless toggle["single_data_writer"] == true
      expires = parse_date(toggle["expires_on"], "#{id}.expires_on")
      add("#{id} must expire no later than contract review") if expires && review && expires > review
    end
  end

  def verify_rollback
    rollback = object(@contract["rollback"], "rollback")
    config = object(rollback["configuration"], "rollback.configuration")
    binary = object(rollback["binary"], "rollback.binary")
    data = object(rollback["data"], "rollback.data")

    add("configuration rollback cannot claim to reverse persisted data") unless config["can_reverse_persisted_data"] == false
    add("configuration rollback must require an implemented toggle") unless config["requires_implemented_toggle"] == true
    add("store rollback must ship a higher forward-fix build") unless binary["action"] == "halt_rollout_then_ship_higher_build_forward_fix"
    add("forced store downgrade is forbidden") unless binary["force_store_downgrade"] == false
    add("binary rollback must acknowledge already-updated users") unless binary["already_updated_users_keep_build"] == true
    add("data rollback must be a forward fix or approved restore") unless data["action"] == "forward_fix_or_restore_with_incident_approval"
    add("destructive data down migration is forbidden") unless data["destructive_down_migration"] == false
    add("data rollback requires a backup/restore drill") unless data["requires_backup_and_restore_drill"] == true
  end

  def verify_artifacts
    artifacts = object(@contract["artifacts"], "artifacts")
    requirements = {
      "android" => %w[aab_or_apk mapping_txt artifact_sha256 mapping_sha256 pg_map_id version_name version_code toolchain_versions],
      "apple" => %w[xcarchive dsym binary_uuid dsym_uuid artifact_sha256 version_name build_number toolchain_versions],
      "shared_binary" => %w[xcframework_zip swiftpm_checksum apple_header_sha256 version_name build_number]
    }

    requirements.each do |platform, required|
      config = object(artifacts[platform], "artifacts.#{platform}")
      actual = array(config["required"], "artifacts.#{platform}.required")
      missing = required - actual
      add("#{platform} artifact manifest missing: #{missing.join(', ')}") unless missing.empty?
      add("#{platform} artifact retention must cover at least 400 days") unless config["retention_days"].is_a?(Integer) && config["retention_days"] >= 400
    end
  end

  def verify_gates_and_readiness
    gates = array(@contract["gates"], "gates")
    owner_areas = array(@contract["owners"], "owners").map { |owner| owner["area"] }
    ids = gates.map { |gate| gate["id"] }
    add("gate ids must be unique") unless ids.compact.uniq.length == ids.compact.length

    gates.each do |gate|
      id = gate["id"] || "unknown"
      add("#{id} has an invalid state") unless ALLOWED_GATE_STATES.include?(gate["state"])
      add("#{id} has an unknown owner area") unless owner_areas.include?(gate["owner_area"])
      add("#{id} needs evidence") if gate["evidence"].to_s.empty?
    end

    blockers = gates.each_with_object([]) do |gate, result|
      result << gate["id"] if gate["required"] == true && gate["state"] != "passed"
    end.sort
    readiness = object(@contract["readiness"], "readiness")
    expected = array(readiness["expected_blockers"], "readiness.expected_blockers").sort
    actual_ready = blockers.empty?
    add("expected blockers disagree with required gate states") unless blockers == expected
    add("release_ready disagrees with required gate states") unless readiness["release_ready"] == actual_ready
  end

  def verify_repository
    release_path = File.join(@project_root, "release.properties")
    properties = read_properties(release_path)
    identity = object(@contract["release_identity"], "release_identity")

    add("contract source must point to release.properties") unless identity["source"] == "labs/02-first-kmp-module/release.properties"
    add("versionName differs from release.properties") unless properties["versionName"] == identity["version_name"]
    add("buildNumber differs from release.properties") unless properties["buildNumber"].to_i == identity["build_number"]
    add("databaseSchema differs from release.properties") unless properties["databaseSchema"].to_i == identity["database_schema"]
    add("diagnosticSchema differs from release.properties") unless properties["diagnosticSchema"].to_i == identity["diagnostic_schema"]

    android = File.read(File.join(@project_root, "androidApp/build.gradle.kts"))
    shared = File.read(File.join(@project_root, "shared/build.gradle.kts"))
    generator = File.read(File.join(@project_root, "iosApp/generate_xcode_project.rb"))
    project = File.read(File.join(@project_root, "iosApp/PocketLabIos.xcodeproj/project.pbxproj"))
    observability_gate = File.read(File.join(@project_root, "scripts/verify-observability.sh"))
    distribution_gate = File.read(File.join(@project_root, "scripts/verify-apple-distribution.sh"))
    diagnostics = File.read(File.join(@project_root, "shared/src/commonMain/kotlin/dev/kmpjourney/pocketlab/diagnostics/RefreshObservability.kt"))
    schema_file = File.join(@project_root, "shared/src/commonMain/sqldelight/databases/#{identity['database_schema']}.db")

    add("Android does not consume the shared version") unless android.include?("versionName = pocketLabVersion")
    add("Android does not consume the shared build number") unless android.include?("versionCode = pocketLabBuild")
    add("KMP framework does not consume the shared version") unless shared.include?("bundleShortVersionString\", pocketLabVersion")
    add("KMP framework does not consume the shared build number") unless shared.include?("bundleVersion\", pocketLabBuild")
    add("Xcode generator does not consume the shared version") unless generator.include?("MARKETING_VERSION\"] = release_version_name")
    add("Xcode generator does not consume the shared build number") unless generator.include?("CURRENT_PROJECT_VERSION\"] = release_build_number")
    add("observability gate does not consume release.properties") unless observability_gate.include?("RELEASE_PROPERTIES=\"$PROJECT_ROOT/release.properties\"")
    add("Apple distribution gate does not consume release.properties") unless distribution_gate.include?("RELEASE_PROPERTIES=\"$PROJECT_ROOT/release.properties\"")

    version_values = project.scan(/MARKETING_VERSION = ([^;]+);/).flatten.uniq
    build_values = project.scan(/CURRENT_PROJECT_VERSION = ([^;]+);/).flatten.uniq
    add("generated Xcode project version drift: #{version_values.join(',')}") unless version_values == [identity["version_name"]]
    add("generated Xcode project build drift: #{build_values.join(',')}") unless build_values == [identity["build_number"].to_s]
    add("SQLDelight schema baseline #{identity['database_schema']}.db is missing") unless File.file?(schema_file)
    add("diagnostic schema source disagrees with release identity") unless diagnostics.include?("add(\"schema=#{identity['diagnostic_schema']}\")")
  rescue Errno::ENOENT => error
    add("repository evidence missing: #{error.message}")
  end

  def read_properties(path)
    File.readlines(path).each_with_object({}) do |line, result|
      stripped = line.strip
      next if stripped.empty? || stripped.start_with?("#")
      key, value = stripped.split("=", 2)
      add("invalid release property: #{stripped}") if key.nil? || value.nil?
      result[key] = value if key && value
    end
  end

  def parse_date(value, field)
    Date.iso8601(value.to_s)
  rescue ArgumentError
    add("#{field} must be an ISO-8601 date")
    nil
  end

  def object(value, field)
    return value if value.is_a?(Hash)
    add("#{field} must be an object")
    {}
  end

  def array(value, field)
    return value if value.is_a?(Array)
    add("#{field} must be an array")
    []
  end

  def positive_integer?(value)
    value.is_a?(Integer) && value.positive?
  end

  def add(message)
    @errors << message
  end
end

def deep_copy(value)
  JSON.parse(JSON.generate(value))
end

def verify_mutations(contract, project_root)
  mutations = [
    ["same primary and backup", lambda { |copy| copy["owners"][0]["backup"] = copy["owners"][0]["primary"] }],
    ["rollout percentage regression", lambda { |copy| copy["rollout"]["android"]["stages"][1]["percent"] = 1 }],
    ["destructive down migration", lambda { |copy| copy["compatibility"]["database"]["destructive_down_migration"] = true }],
    ["false readiness claim", lambda { |copy| copy["readiness"]["release_ready"] = true }],
    ["shared binary version drift", lambda { |copy| copy["compatibility"]["shared_binary"]["version_name"] = "9.9.9" }],
    ["unknown gate owner", lambda { |copy| copy["gates"][0]["owner_area"] = "everyone" }]
  ]

  failures = []
  mutations.each do |label, mutation|
    copy = deep_copy(contract)
    mutation.call(copy)
    errors = ContractVerifier.new(copy, project_root: project_root, check_repository: false).verify
    failures << label if errors.empty?
  end
  failures
end

lab_root = File.expand_path("..", __dir__)
repository_root = File.expand_path("../..", lab_root)
project_root = File.join(repository_root, "labs/02-first-kmp-module")
contract_path = ARGV[0] ? File.expand_path(ARGV[0]) : File.join(lab_root, "release-contract.json")

begin
  contract = JSON.parse(File.read(contract_path))
rescue JSON::ParserError, Errno::ENOENT => error
  warn "Release contract could not be read: #{error.message}"
  exit 1
end

errors = ContractVerifier.new(contract, project_root: project_root).verify
unless errors.empty?
  warn "Release contract failed:"
  errors.each { |error| warn "- #{error}" }
  exit 1
end

mutation_failures = verify_mutations(contract, project_root)
unless mutation_failures.empty?
  warn "Release contract mutation checks failed to reject: #{mutation_failures.join(', ')}"
  exit 1
end

required_gates = contract["gates"].select { |gate| gate["required"] == true }
passed_gates = required_gates.count { |gate| gate["state"] == "passed" }
blockers = contract["readiness"]["expected_blockers"]
android_stages = contract["rollout"]["android"]["stages"].map { |stage| stage["percent"] }
ios_stages = contract["rollout"]["ios"]["stages"].map { |stage| stage["percent"] }

puts "Production release contract passed."
puts "Release identity: #{contract['release_identity']['version_name']} (#{contract['release_identity']['build_number']}) from one properties file"
puts "Owners: #{contract['owners'].length} areas, each with primary and backup"
puts "Required gates: #{passed_gates}/#{required_gates.length} passed"
puts "Release ready: #{contract['readiness']['release_ready']}"
puts "Explicit blockers: #{blockers.join(', ')}"
puts "Android rollout: #{android_stages.join(' -> ')}%"
puts "iOS phased release: #{ios_stages.join(' -> ')}%"
puts "Rollback modes: configuration != binary != data"
puts "Negative mutation checks: 6/6 rejected"
