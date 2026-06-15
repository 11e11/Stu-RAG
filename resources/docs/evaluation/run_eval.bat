@echo off
chcp 65001 >nul
echo ========================================
echo RAGAS 批量评测数据收集脚本
echo ========================================
echo.

REM 防止系统休眠
echo [1/2] 设置电源选项，防止休眠...
powercfg /change standby-timeout-ac 0 2>nul
powercfg /change standby-timeout-dc 0 2>nul
powercfg /change monitor-timeout-ac 0 2>nul
powercfg /change monitor-timeout-dc 0 2>nul
echo 电源选项已设置（需要管理员权限才能生效）

echo.
echo [2/2] 开始运行评测脚本...
echo.

REM 设置日志文件路径（使用当前时间）
for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value') do set datetime=%%I
set LOG_FILE=resources\docs\evaluation\eval_log_%datetime:~0,8%_%datetime:~8,6%.log

echo 日志文件: %LOG_FILE%
echo 结果文件: resources\docs\evaluation\ragas_eval_with_answers.json
echo.

REM 运行脚本并保存日志
python resources/docs/evaluation/batch_collect.py ^
    --base-url http://localhost:9090/api/ragent ^
    --username admin ^
    --password admin ^
    --input resources/docs/evaluation/ragas_eval_dataset.json ^
    --output resources/docs/evaluation/ragas_eval_with_answers.json > %LOG_FILE% 2>&1

REM 显示日志内容
type %LOG_FILE%

echo.
echo ========================================
echo 评测完成！
echo 数据文件: resources\docs\evaluation\ragas_eval_with_answers.json
echo 日志文件: %LOG_FILE%
echo ========================================
pause
