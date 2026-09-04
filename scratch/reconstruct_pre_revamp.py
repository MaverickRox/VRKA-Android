import json, os, subprocess

transcript_path = r'C:\Users\Shukla\.gemini\antigravity\brain\39750504-46fd-4621-b41f-792860adee88\.system_generated\logs\transcript_full.jsonl'
os.makedirs('scratch/pre_revamp', exist_ok=True)

target_names = [
    'HomeScreen.kt',
    'JobsScreen.kt',
    'SettingsScreen.kt',
    'VrkaRoot.kt',
    'Theme.kt',
    'ActiveDownloadStrip.kt'
]

targets = {}
for t in target_names:
    p = f"776afce:app/src/main/java/com/mvrk/vrka/{t}"
    try:
        content = subprocess.check_output(['git', 'show', p], text=True, encoding='utf-8')
        targets[t] = content
        print(f"Loaded {t} from 776afce ({len(content)} chars)")
    except Exception as e:
        print(f"Failed to load {t}: {e}")

with open(transcript_path, 'r', encoding='utf-8') as f:
    for line in f:
        data = json.loads(line)
        step = data.get('step_index', 0)
        if step > 7549:
            break
        
        for tc in data.get('tool_calls', []):
            name = tc.get('name')
            args = tc.get('args', {})
            if name == 'write_to_file':
                tf = args.get('TargetFile', '')
                for t in targets:
                    if tf.endswith(t):
                        targets[t] = args.get('CodeContent', '')
                        print(f"Step {step}: write_to_file {t} ({len(targets[t])} chars)")
            elif name == 'replace_file_content':
                tf = args.get('TargetFile', '')
                for t in targets:
                    if tf.endswith(t):
                        content = targets[t]
                        target_chunk = args.get('TargetContent', '')
                        repl_chunk = args.get('ReplacementContent', '')
                        if content is not None:
                            if target_chunk in content:
                                targets[t] = content.replace(target_chunk, repl_chunk, 1)
                            else:
                                # Try normalizing newlines
                                tc_norm = target_chunk.replace('\r\n', '\n')
                                c_norm = content.replace('\r\n', '\n')
                                if tc_norm in c_norm:
                                    c_norm = c_norm.replace(tc_norm, repl_chunk.replace('\r\n', '\n'), 1)
                                    targets[t] = c_norm
                                else:
                                    print(f"Step {step}: WARNING target chunk not found in {t}!")
                        else:
                            print(f"Step {step}: WARNING content is None for {t}!")

for t, content in targets.items():
    if content:
        out_path = os.path.join('scratch/pre_revamp', t)
        with open(out_path, 'w', encoding='utf-8') as out_f:
            out_f.write(content)
        print(f"SUCCESS Saved {t}: {len(content)} chars")
    else:
        print(f"Could not reconstruct {t}!")
