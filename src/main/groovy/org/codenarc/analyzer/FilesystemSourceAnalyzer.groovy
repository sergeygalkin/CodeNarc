/*
 * Copyright 2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codenarc.analyzer

import org.codenarc.results.DirectoryResults
import org.codenarc.results.FileResults
import org.codenarc.results.Results
import org.codenarc.ruleset.RuleSet
import org.codenarc.source.SourceCode
import org.codenarc.source.SourceFile
import org.codenarc.util.PathUtil
import org.codenarc.util.WildcardPattern
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * SourceAnalyzer implementation that recursively processes files from the file system.
 *
 * @author Chris Mair
 */
class FilesystemSourceAnalyzer extends AbstractSourceAnalyzer {

    private static final String SEP = '/'
    private static final String DEFAULT_INCLUDES = '**/*.groovy'
    private static final Logger LOG = LoggerFactory.getLogger(FilesystemSourceAnalyzer)

    /**
     * The base (root) directory. Must not be null or empty.
     */
    String baseDirectory

    /**
     * The ant-style pattern of files to include in the analysis. Defaults to match all
     * files with names ending with '.groovy'. If null, match all
     * files/directories. This pattern can optionally contain wildcards: '**', '*' and '?'.
     * All file separators within paths are normalized to the standard '/' separator,
     * so use the '/' separator within this pattern where necessary. Example:
     * "&#42;&#42;/*.groovy". If both <code>includes</code> and <code>excludes</code>
     * are specified, then only files/directories that match at least one of the
     * <code>includes</code> and none of the <code>excludes</code> are analyzed.
     */
    String includes = DEFAULT_INCLUDES

    /**
     * The ant-style pattern of files to exclude from the analysis. If null, exclude no
     * files/directories. This pattern can optionally contain wildcards: '**', '*' and '?'.
     * All file separators within paths are normalized to the standard '/' separator,
     * so use the '/' separator within this pattern where necessary. Example:
     * "&#42;&#42;/*.groovy". If both <code>includes</code> and <code>excludes</code>
     * are specified, then only files/directories that match at least one of the
     * <code>includes</code> and none of the <code>excludes</code> are analyzed.
     */
    String excludes

    private WildcardPattern includesPattern
    private WildcardPattern excludesPattern

    /**
     * Whether to throw an exception if errors occur parsing source files (true), or just log the errors (false)
     */
    boolean failOnError = false

    /**
     * Analyze the source with the configured directory tree(s) using the specified RuleSet and return the report results.
     * @param ruleset - the RuleSet to apply to each of the (applicable) files in the source directories
     * @return the results from applying the RuleSet to all of the files in the source directories
     */
    @Override
    Results analyze(RuleSet ruleSet) {
        assert baseDirectory
        assert ruleSet

        initializeWildcardPatterns()
        def reportResults = new DirectoryResults()
        def dirResults = processDirectory('', ruleSet)
        reportResults.addChild(dirResults)
        reportResults
    }

    @Override
    List getSourceDirectories() {
        [baseDirectory]
    }

    @SuppressWarnings(['CatchThrowable', 'NestedBlockDepth'])
    private DirectoryResults processDirectory(String dir, RuleSet ruleSet) {
        def dirResults = new DirectoryResults(dir)
        def dirFile = new File(baseDirectory, dir)
        dirFile.eachFile { file ->
            def dirPrefix = dir ? dir + SEP : dir
            def filePath = dirPrefix + file.name
            if (file.directory) {
                def subdirResults = processDirectory(filePath, ruleSet)
                // If any of the descendent directories have matching files, then include in final results
                if (subdirResults.getTotalNumberOfFiles(true)) {
                    dirResults.addChild(subdirResults)
                }
            }
            else {
                try {
                    processFile(filePath, dirResults, ruleSet)
                } catch (Throwable t) {
                    LOG.warn("Error processing file: '" + filePath + "'; " + t)
                    if (failOnError) {
                        throw new AnalyzerException("Error analyzing source file: $filePath; $t")
                    }
                }
            }
        }
        dirResults
    }

    private void processFile(String filePath, DirectoryResults dirResults, RuleSet ruleSet) {
        def file = new File(baseDirectory, filePath)
        def sourceFile = new SourceFile(file)
        if (matches(sourceFile)) {
            List allViolations = collectViolations(sourceFile, ruleSet)
            def fileResults = new FileResults(filePath, allViolations, sourceFile)
            dirResults.numberOfFilesInThisDirectory++
            dirResults.addChild(fileResults)
        }
    }

    protected boolean matches(SourceCode sourceFile) {
        String path = sourceFile.path
        String pathWithoutBaseDir = PathUtil.removeLeadingSlash(path - baseDirectory)
        return matchesIncludesAndExcludes(path) || matchesIncludesAndExcludes(pathWithoutBaseDir)
    }

    private boolean matchesIncludesAndExcludes(String path) {
        return includesPattern.matches(path) && !excludesPattern.matches(path)
    }

    protected void initializeWildcardPatterns() {
        includesPattern = new WildcardPattern(includes)
        excludesPattern = new WildcardPattern(excludes, false)  // do not match by default
    }
}
