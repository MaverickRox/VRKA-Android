import json, os

transcript_path = r'C:\Users\Shukla\.gemini\antigravity\brain\39750504-46fd-4621-b41f-792860adee88\.system_generated\logs\transcript_full.jsonl'
os.makedirs('scratch/pre_revamp_clean', exist_ok=True)

# Step 7557 has VrkaRoot.kt
# Step 7473 has SettingsScreen.kt
# Step 7467 has Theme.kt before step 7468 replace

with open(transcript_path, 'r', encoding='utf-8') as f:
    for line in f:
        data = json.loads(line)
        step = data.get('step_index', 0)
        content = data.get('content', '')
        
        if step == 7557:
            # VrkaRoot.kt
            lines = []
            for l in content.splitlines():
                if ': ' in l and l.split(': ', 1)[0].isdigit():
                    lines.append(l.split(': ', 1)[1])
            with open('scratch/pre_revamp_clean/VrkaRoot.kt', 'w', encoding='utf-8') as out:
                out.write('\n'.join(lines) + '\n')
            print(f"Saved pre-revamp VrkaRoot.kt ({len(lines)} lines)")
            
        elif step == 7473:
            # SettingsScreen.kt
            lines = []
            for l in content.splitlines():
                if ': ' in l and l.split(': ', 1)[0].isdigit():
                    lines.append(l.split(': ', 1)[1])
            with open('scratch/pre_revamp_clean/SettingsScreen.kt', 'w', encoding='utf-8') as out:
                out.write('\n'.join(lines) + '\n')
            print(f"Saved pre-revamp SettingsScreen.kt ({len(lines)} lines)")
