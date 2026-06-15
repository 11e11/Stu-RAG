-- ============================================
-- 升级脚本: v1.2 -> v1.3
-- 功能: 添加全文检索支持 (zhparser + tsvector)
-- ============================================

-- 1. 创建 zhparser 扩展（需要先编译安装 SCWS 和 zhparser）
-- 如果扩展已存在会报错，可以忽略
CREATE EXTENSION IF NOT EXISTS zhparser;

-- 2. 创建中文全文搜索配置
-- 使用 zhparser 作为解析器
CREATE TEXT SEARCH CONFIGURATION chinese (PARSER = zhparser);

-- 关键步骤：添加 token 类型映射
-- zhparser 产出的 token 类型必须映射到 simple 词典
-- 否则 PG 会丢弃所有 token，to_tsvector 返回空
ALTER TEXT SEARCH CONFIGURATION chinese ADD MAPPING FOR n,v,a,i,e,l WITH simple;

-- 3. 为 t_knowledge_vector 表添加 tsv 列
-- tsvector 类型用于存储全文检索向量
ALTER TABLE t_knowledge_vector ADD COLUMN IF NOT EXISTS tsv tsvector;

-- 4. 创建 GIN 索引以加速全文检索
-- GIN 索引是 tsvector 类型最高效的索引方式
CREATE INDEX IF NOT EXISTS idx_kv_tsv ON t_knowledge_vector USING gin(tsv);

-- 5. 创建自动维护 tsv 列的触发器函数
-- 当 content 列更新时，自动重新生成 tsv
CREATE OR REPLACE FUNCTION t_knowledge_vector_tsv_trigger()
RETURNS trigger AS $$
BEGIN
    NEW.tsv := to_tsvector('chinese', COALESCE(NEW.content, ''));
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

-- 6. 创建触发器
-- 在 INSERT 或 UPDATE 时自动调用触发器函数
DROP TRIGGER IF EXISTS tsvectorupdate ON t_knowledge_vector;
CREATE TRIGGER tsvectorupdate
    BEFORE INSERT OR UPDATE OF content
    ON t_knowledge_vector
    FOR EACH ROW
    EXECUTE FUNCTION t_knowledge_vector_tsv_trigger();

-- 7. 回填已有数据的 tsv 列
-- 对于已存在的记录，需要手动更新 tsv
UPDATE t_knowledge_vector
SET tsv = to_tsvector('chinese', COALESCE(content, ''))
WHERE tsv IS NULL;

-- 8. 添加注释
COMMENT ON COLUMN t_knowledge_vector.tsv IS '全文检索向量，由 zhparser 分词自动生成';
COMMENT ON FUNCTION t_knowledge_vector_tsv_trigger() IS '自动维护 tsv 列的触发器函数';

-- ============================================
-- 文档上传幂等：新增文件内容哈希字段
-- ============================================
ALTER TABLE t_knowledge_document ADD COLUMN IF NOT EXISTS file_content_hash VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_doc_content_hash ON t_knowledge_document (file_content_hash, file_size);
COMMENT ON COLUMN t_knowledge_document.file_content_hash IS '文件内容MD5哈希，用于幂等去重';

-- ============================================
-- 验证迁移结果
-- ============================================
-- 可以执行以下语句验证：
--
-- -- 检查扩展是否安装
-- SELECT * FROM pg_extension WHERE extname = 'zhparser';
--
-- -- 检查搜索配置
-- SELECT cfgname FROM pg_ts_config WHERE cfgname = 'chinese';
--
-- -- 检查 tsv 列是否填充
-- SELECT id, tsv FROM t_knowledge_vector LIMIT 5;
--
-- -- 测试全文检索
-- SELECT id, content, ts_rank_cd(tsv, to_tsquery('chinese', '社保')) AS rank
-- FROM t_knowledge_vector
-- WHERE tsv @@ to_tsquery('chinese', '社保')
-- ORDER BY rank DESC
-- LIMIT 10;
