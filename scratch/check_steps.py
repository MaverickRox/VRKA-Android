import json

transcript_path = r'C:\Users\Shukla\.gemini\antigravity\brain\39750504-46fd-4621-b41f-792860adee88\.system_generated\logs\transcript_full.jsonl'

with open(transcript_path, 'r', encoding='utf-8') as f:
    for line in f:
        data = json.loads(line)
        step = data.get('step_index', 0)
        if step in [7557, 7467, 7473, 7234, 7228]:
            print(f"Step {step}: type={data.get('type')}, content_len={len(data.get('content', ''))}")
