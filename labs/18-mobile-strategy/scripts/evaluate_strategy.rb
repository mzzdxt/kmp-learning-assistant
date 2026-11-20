#!/usr/bin/env ruby

require "json"

MODEL_PATH = File.expand_path("../decision-model.json", __dir__)

def fail_model(message)
  warn "Mobile strategy model invalid: #{message}"
  exit 1
end

def validate!(model)
  scale = model.fetch("ratingScale")
  minimum = scale.fetch("minimum")
  maximum = scale.fetch("maximum")
  fail_model("rating scale must start below its maximum") unless minimum < maximum

  criteria = model.fetch("criteria")
  criterion_ids = criteria.map { |criterion| criterion.fetch("id") }
  fail_model("criterion ids must be unique") unless criterion_ids.uniq.length == criterion_ids.length

  strategies = model.fetch("strategies")
  strategy_ids = strategies.map { |strategy| strategy.fetch("id") }
  fail_model("strategy ids must be unique") unless strategy_ids.uniq.length == strategy_ids.length

  strategies.each do |strategy|
    ratings = strategy.fetch("ratings")
    fail_model("#{strategy.fetch('id')} must rate every criterion") unless ratings.keys.sort == criterion_ids.sort
    ratings.each do |criterion, rating|
      unless rating.is_a?(Numeric) && rating.between?(minimum, maximum)
        fail_model("#{strategy.fetch('id')}.#{criterion} is outside #{minimum}..#{maximum}")
      end
    end
  end

  model.fetch("scenarios").each do |scenario|
    weights = scenario.fetch("weights")
    unknown_weights = weights.keys - criterion_ids
    fail_model("#{scenario.fetch('id')} has unknown weights: #{unknown_weights.join(', ')}") unless unknown_weights.empty?
    fail_model("#{scenario.fetch('id')} must weight every criterion") unless weights.keys.sort == criterion_ids.sort
    fail_model("#{scenario.fetch('id')} has a negative weight") if weights.values.any? { |weight| !weight.is_a?(Numeric) || weight.negative? }
    fail_model("#{scenario.fetch('id')} has no active criteria") unless weights.values.sum.positive?

    scenario.fetch("gates").each do |gate|
      criterion = gate.fetch("criterion")
      fail_model("#{scenario.fetch('id')} gates unknown criterion #{criterion}") unless criterion_ids.include?(criterion)
      threshold = gate.fetch("minimum")
      fail_model("#{scenario.fetch('id')} gate #{criterion} is outside the rating scale") unless threshold.between?(minimum, maximum)
    end

    expected = scenario.fetch("expectedWinner")
    fail_model("#{scenario.fetch('id')} expects unknown strategy #{expected}") unless strategy_ids.include?(expected)
  end
end

def evaluate(model, scenario)
  maximum = model.fetch("ratingScale").fetch("maximum").to_f
  weights = scenario.fetch("weights")
  denominator = maximum * weights.values.sum

  model.fetch("strategies").map do |strategy|
    ratings = strategy.fetch("ratings")
    failed_gates = scenario.fetch("gates").each_with_object([]) do |gate, failures|
      criterion = gate.fetch("criterion")
      failures << gate if ratings.fetch(criterion) < gate.fetch("minimum")
    end
    weighted_total = weights.sum { |criterion, weight| ratings.fetch(criterion) * weight }

    {
      "id" => strategy.fetch("id"),
      "label" => strategy.fetch("label"),
      "eligible" => failed_gates.empty?,
      "failedGates" => failed_gates,
      "score" => (weighted_total * 100.0 / denominator).round(1)
    }
  end.sort_by { |result| [result.fetch("eligible") ? 0 : 1, -result.fetch("score"), result.fetch("label")] }
end

def winner(results)
  results.find { |result| result.fetch("eligible") }
end

def check!(model)
  chosen = []
  model.fetch("scenarios").each do |scenario|
    results = evaluate(model, scenario)
    actual = winner(results)
    fail_model("#{scenario.fetch('id')} leaves no eligible strategy") unless actual

    expected = scenario.fetch("expectedWinner")
    unless actual.fetch("id") == expected
      fail_model("#{scenario.fetch('id')} expected #{expected}, got #{actual.fetch('id')}")
    end
    chosen << actual.fetch("id")
  end

  strategy_ids = model.fetch("strategies").map { |strategy| strategy.fetch("id") }
  unless chosen.uniq.sort == strategy_ids.sort
    fail_model("the example scenarios must demonstrate that each strategy can win")
  end

  puts "Mobile strategy model passed."
  puts "#{strategy_ids.length} strategies, #{model.fetch('criteria').length} criteria, #{model.fetch('scenarios').length} scenarios"
  puts "Hard constraints run before weighted fit."
  puts "Each strategy wins one scenario; no universal winner is encoded."
end

def print_markdown(model)
  puts "# Mobile strategy evaluation"
  puts
  puts "The score is a scenario-specific fit hypothesis on a 0–100 scale. Hard constraints run before weights."

  model.fetch("scenarios").each do |scenario|
    results = evaluate(model, scenario)
    selected = winner(results)
    eligible = results.select { |result| result.fetch("eligible") }
    runner_up = eligible[1]

    puts
    puts "## #{scenario.fetch('label')}"
    puts
    puts scenario.fetch("decisionQuestion")
    puts
    scenario.fetch("gates").each do |gate|
      puts "- Gate: #{gate.fetch('criterion')} >= #{gate.fetch('minimum')} — #{gate.fetch('reason')}"
    end
    puts
    puts "| Strategy | Gate | Weighted fit |"
    puts "|---|---|---:|"
    results.each do |result|
      gate = if result.fetch("eligible")
        "pass"
      else
        result.fetch("failedGates").map { |failed| "fail #{failed.fetch('criterion')}" }.join(", ")
      end
      puts "| #{result.fetch('label')} | #{gate} | #{format('%.1f', result.fetch('score'))} |"
    end
    puts
    puts "Candidate: **#{selected.fetch('label')}**."
    if runner_up
      gap = (selected.fetch("score") - runner_up.fetch("score")).round(1)
      puts "Eligible runner-up gap: #{format('%.1f', gap)} points."
      puts "A gap below 5 points requires a time-boxed implementation spike before commitment." if gap < 5
    else
      puts "Only one strategy passes the declared gates; validate those gates with product and platform owners."
    end
  end
end

model = JSON.parse(File.read(MODEL_PATH))
validate!(model)

case ARGV.first
when "--check"
  check!(model)
when nil, "--markdown"
  print_markdown(model)
else
  warn "Usage: #{File.basename($PROGRAM_NAME)} [--check|--markdown]"
  exit 2
end
