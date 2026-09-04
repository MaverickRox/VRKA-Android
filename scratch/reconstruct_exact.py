import json, os

transcript_path = r'C:\Users\Shukla\.gemini\antigravity\brain\39750504-46fd-4621-b41f-792860adee88\.system_generated\logs\transcript_full.jsonl'
os.makedirs('scratch/pre_revamp_exact', exist_ok=True)

# Step map of full view:
# Step 6900 -> Theme.kt (in step 6901 response)
# Step 6902 -> HomeScreen.kt (in step 6903 response)
# Step 6904 -> JobsScreen.kt (in step 6905 response)
# Step 6906 -> SettingsScreen.kt (in step 6907 response)
# Step 6908 -> ActiveDownloadStrip.kt (in step 6909 response)

files = {
    6901: 'Theme.kt',
    6903: 'HomeScreen.kt',
    6905: 'JobsScreen.kt',
    6907: 'SettingsScreen.kt',
    6909: 'ActiveDownloadStrip.kt'
}

loaded_files = {}

def parse_view_content(content):
    lines = []
    for l in content.splitlines():
        if ': ' in l:
            part0, part1 = l.split(': ', 1)
            if part0.strip().isdigit():
                lines.append(part1)
    return '\n'.join(lines) + '\n'

with open(transcript_path, 'r', encoding='utf-8') as f:
    for line in f:
        data = json.loads(line)
        step = data.get('step_index', 0)
        content = data.get('content', '')
        if step in files:
            fname = files[step]
            parsed = parse_view_content(content)
            loaded_files[fname] = parsed
            print(f"Loaded {fname} at step {step}: {len(parsed)} chars, {len(parsed.splitlines())} lines")

# VrkaRoot.kt was viewed at step 7556 (step 7557 response)
with open(transcript_path, 'r', encoding='utf-8') as f:
    for line in f:
        data = json.loads(line)
        step = data.get('step_index', 0)
        if step == 7557:
            loaded_files['VrkaRoot.kt'] = parse_view_content(data.get('content', ''))
            print(f"Loaded VrkaRoot.kt at step 7557: {len(loaded_files['VrkaRoot.kt'])} chars")

# Now apply all replace_file_content calls between 6910 and 7548
with open(transcript_path, 'r', encoding='utf-8') as f:
    for line in f:
        data = json.loads(line)
        step = data.get('step_index', 0)
        if step < 6910:
            continue
        if step > 7548:
            break
        
        for tc in data.get('tool_calls', []):
            if tc.get('name') == 'replace_file_content':
                tf = tc.get('args', {}).get('TargetFile', '')
                for fname in loaded_files:
                    if fname == 'VrkaRoot.kt':
                        continue # VrkaRoot was loaded at 7557 directly
                    if tf.endswith(fname):
                        content = loaded_files[fname]
                        tgt = tc.get('args', {}).get('TargetContent', '')
                        rep = tc.get('args', {}).get('ReplacementContent', '')
                        if tgt in content:
                            loaded_files[fname] = content.replace(tgt, rep, 1)
                            print(f"Applied replace on {fname} at step {step}")
                        else:
                            tc_norm = tgt.replace('\r\n', '\n')
                            c_norm = content.replace('\r\n', '\n')
                            if tc_norm in c_norm:
                                loaded_files[fname] = c_norm.replace(tc_norm, rep.replace('\r\n', '\n'), 1)
                                print(f"Applied normalized replace on {fname} at step {step}")
                            else:
                                print(f"ERROR: Could not apply replace on {fname} at step {step}")
            elif tc.get('name') == 'write_to_file':
                tf = tc.get('args', {}).get('TargetFile', '')
                for fname in loaded_files:
                    if fname == 'VrkaRoot.kt':
                        continue
                    if tf.endswith(fname):
                        loaded_files[fname] = tc.get('args', {}).get('CodeContent', '')
                        print(f"Applied write_to_file on {fname} at step {step}")

for fname, content in loaded_files.items():
    out_path = os.path.join('scratch/pre_revamp_exact', fname)
    with open(out_path, 'w', encoding='utf-8') as out_f:
        out_f.write(content)
    print(f"Saved {fname}: {len(content)} chars, {len(content.splitlines())} lines")
