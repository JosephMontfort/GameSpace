import os, re

sys_file = 'java/mx/xperience/gamespace/utils/SysfsController.kt'
with open(sys_file, 'r') as f:
    sys_code = f.read()

# 1. Fix UCLAMP boundaries for the 1024 kernel scale
sys_code = sys_code.replace('setUclamp(0, 60)', 'setUclamp(0, 614)')
sys_code = sys_code.replace('setUclamp(20, 100)', 'setUclamp(204, 1024)')
sys_code = sys_code.replace('setUclamp(40, 100)', 'setUclamp(409, 1024)')

# 2. Disable the GPU thermal death-loop
gpu_pat = r'write\(\s*"/sys/class/kgsl/kgsl-3d0/force_clk_on",\s*if\s*\(enabled\)\s*"1"\s*else\s*"0"\s*\)'
sys_code = re.sub(gpu_pat, '// force_clk_on disabled to prevent severe thermal throttling', sys_code)

with open(sys_file, 'w') as f:
    f.write(sys_code)

fps_file = 'java/mx/xperience/gamespace/utils/FPSMonitor.kt'
with open(fps_file, 'r') as f:
    fps_code = f.read()

# 3. Fix the FPS String Parser to handle "fps: XX.X duration:..."
fps_pat = r'val value = file\.readText\(\)\.trim\(\)\.toFloat\(\)\.roundToInt\(\)\s*if\s*\(value\s*in\s*10\.\.240\)\s*value\s*else\s*null'

new_fps = """val text = file.readText().trim()
            var value = 0f
            if (text.contains("fps:")) {
                value = text.substringAfter("fps:").trim().substringBefore(" ").toFloatOrNull() ?: 0f
            } else {
                value = text.toFloatOrNull() ?: 0f
            }
            var intValue = value.roundToInt()
            
            // Compensate for 240Hz TE heartbeat signals
            if (intValue > 120) intValue /= 2
            
            if (intValue in 10..120) intValue else null"""

fps_code = re.sub(fps_pat, new_fps, fps_code)

with open(fps_file, 'w') as f:
    f.write(fps_code)

print("GameSpace internal logic patched successfully!")
