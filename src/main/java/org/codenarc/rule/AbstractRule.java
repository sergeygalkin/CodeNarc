/*
 * Copyright 2008 the original author or authors.
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
package org.codenarc.rule;

import org.apache.log4j.Logger;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codenarc.source.SourceCode;
import org.codenarc.source.SourceCodeCriteria;
import org.codenarc.util.ImportUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Abstract superclass for Rules.
 * <p/>
 * Each subclass must define an <code>name</code> property (String) and a <code>priority</code> property
 * (integer 1..3).
 *
 * @author Chris Mair
 * @author Hamlet D'Arcy
 */
public abstract class AbstractRule implements Rule {
    private static final Logger LOG = Logger.getLogger(AbstractRule.class);

    /**
     * Flag indicating whether this rule should be enabled (applied). Defaults to true.
     * If set to false, this rule will not produce any violations.
     */
    private boolean enabled = true;

    /**
     * This rule is only applied to source code (file) pathnames matching this regular expression.
     */
    private String applyToFilesMatching;

    /**
     * This rule is NOT applied to source code (file) pathnames matching this regular expression.
     */
    private String doNotApplyToFilesMatching;

    /**
     * This rule is only applied to source code (file) names matching this value. The name may optionally
     * contain a path. If a path is specified, then the source code path must match it. If no path is
     * specified, then only the source code (file) name is compared (i.e., its path is ignored).
     * The value may optionally be a comma-separated list of names, in which case one of the names must match.
     * The name(s) may optionally include wildcard characters ('*' or '?').
     */
    private String applyToFileNames;

    /**
     * This rule is NOT applied to source code (file) names matching this value. The name may optionally
     * contain a path. If a path is specified, then the source code path must match it. If no path is
     * specified, then only the source code (file) name is compared (i.e., its path is ignored).
     * The value may optionally be a comma-separated list of names, in which case any one of the names can match.
     * The name(s) may optionally include wildcard characters ('*' or '?').
     */
    private String doNotApplyToFileNames;

    /**
     * If not null, this is used as the message for all violations of this rule, overriding any
     * message generated by the concrete rule subclass. Defaults to null. Note that setting this
     * to an empty string "hides" the message, if any, generated by the actual rule.
     */
    private String violationMessage;

    /**
     * If not null, this is used as the description text for this rule, overriding any
     * description text found in the i18n resource bundles. Defaults to null.
     */
    private String description;

    /**
     * @return the unique name for this rule
     */
    public abstract String getName();

    /**
     * Set the unique name for this rule
     * @param name - the name for this rule; this should be unique 
     */
    public abstract void setName(String name);

    /**
     * @return the priority of this rule, between 1 (highest priority) and 3 (lowest priority), inclusive.
     */
    public abstract int getPriority();

    /**
     * Set the priority for this rule
     * @param priority - the priority of this rule, between 1 (highest priority) and 3 (lowest priority), inclusive.
     */
    public abstract void setPriority(int priority);

    /**
     * @return the required compiler phase (as in {@link org.codehaus.groovy.control.Phases})
     * of the AST of the {@link SourceCode}
     * handed to the rule via {@link #applyTo(SourceCode sourceCode)}
     */
    public int getRequiredAstCompilerPhase() {
        return SourceCode.DEFAULT_COMPILER_PHASE;
    }
    
    /**
     * Apply this rule to the specified source and return a list of violations (or an empty List)
     * @param sourceCode - the source to apply this rule to
     * @param violations - the List of violations to which new violations from this rule are to be added
     */
    public abstract void applyTo(SourceCode sourceCode, List<Violation> violations);

    /**
     * Apply this rule to the specified source and return a list of violations (or an empty List).
     * This implementation delegates to the abstract applyCode(SourceCode,List), provided by
     * concrete subclasses. This template method simplifies subclass implementations and also
     * enables common handling of enablement logic.
     * @param sourceCode - the source to apply this rule to
     * @return the List of violations; may be empty
     */
    public List<Violation> applyTo(SourceCode sourceCode) throws Throwable {
        try {
            validateAstCompilerPhase(sourceCode);
            validate();
            List<Violation> violations = new ArrayList<Violation>();
            if (shouldApplyThisRuleTo(sourceCode)) {
                applyTo(sourceCode, violations);
            }
            overrideViolationMessageIfNecessary(violations);
            return violations;
        } catch(Throwable t) {
            LOG.error("Error from [" + getClass().getName() + "] processing source file [" + sourceCode.getPath() + "]", t);
            throw t;
        }
    }

    private void validateAstCompilerPhase(SourceCode sourceCode) {
        if (sourceCode.getAstCompilerPhase() != getRequiredAstCompilerPhase()) {
            throw new IllegalArgumentException("This rule requires SourceCode with AST compiler phase '"
                    + getRequiredAstCompilerPhase() + "', but was handed one with AST compiler phase '"
                    + sourceCode.getAstCompilerPhase() + "'");
        }
    }

    /**
     * Allows rules to check whether preconditions are satisfied and short-circuit execution
     * (i.e., do nothing) if those preconditions are not satisfied. Return true by default.
     * This method is provided as a placeholder so subclasses can optionally override. 
     * @return true if all preconditions for this rule are satisfied
     */
    public boolean isReady() {
        return true;
    }

    /**
     * Allows rules to perform validation. Do nothing by default.
     * This method is provided as a placeholder so subclasses can optionally override.
     * Subclasses will typically use <code>assert</code> calls to verify required preconditions.
     */
    public void validate() {
    }

    public String toString() {
        return String.format(
                "%s[name=%s, priority=%s]",
                getClassNameNoPackage(),
                getName(),
                getPriority()
        );
    }

    /**
     * Create and return a new Violation for this rule and the specified values
     * @param lineNumber - the line number for the violation; may be null
     * @param sourceLine - the source line for the violation; may be null
     * @param message - the message for the violation; may be null
     * @return a new Violation object
     */
    protected Violation createViolation(Integer lineNumber, String sourceLine, String message) {
        Violation violation = new Violation();
        violation.setRule(this);
        violation.setSourceLine(sourceLine);
        violation.setLineNumber(lineNumber);
        violation.setMessage(message);
        return violation;
    }

    /**
     * Create and return a new Violation for this rule and the specified values
     * @param lineNumber - the line number for the violation; may be null
     * @return a new Violation object
     */
    @Deprecated // should really supply an AST Node
    protected Violation createViolation(Integer lineNumber, String message) {
        return createViolation(lineNumber, null, message);
    }

    /**
     * Create and return a new Violation for this rule and the specified values
     * @param lineNumber - the line number for the violation; may be null
     * @return a new Violation object
     */
    @Deprecated
    protected Violation createViolation(Integer lineNumber) {
        return createViolation(lineNumber, null, null);
    }

    /**
     * Create a new Violation for the AST node.
     * @param sourceCode - the SourceCode
     * @param node - the Groovy AST Node
     * @param message - the message for the violation; defaults to null
     */
    protected Violation createViolation(SourceCode sourceCode, ASTNode node, String message) {
        String sourceLine = sourceCode.line(node.getLineNumber()-1);
        return createViolation(node.getLineNumber(), sourceLine, message);
    }

    /**
     * Create a new Violation for the AST node.
     * @param sourceCode - the SourceCode
     * @param node - the Groovy AST Node
     */
    @Deprecated // should really supply a message
    protected Violation createViolation(SourceCode sourceCode, ASTNode node) {
        return createViolation(sourceCode, node, null);
    }


    /**
     * Create and return a new Violation for this rule and the specified import
     * @param sourceCode - the SourceCode
     * @param importNode - the ImportNode for the import triggering the violation
     * @return a new Violation object
     */
    protected Violation createViolationForImport(SourceCode sourceCode, ImportNode importNode, String message) {
        Map importInfo = ImportUtil.sourceLineAndNumberForImport(sourceCode, importNode);
        Violation violation = new Violation();
        violation.setRule(this);
        violation.setSourceLine((String) importInfo.get("sourceLine"));
        violation.setLineNumber((Integer) importInfo.get("lineNumber"));
        violation.setMessage(message);
        return violation; 
    }

    /**
     * Create and return a new Violation for this rule and the specified import
     * @param sourceCode - the SourceCode
     * @param importNode - the ImportNode for the import triggering the violation
     * @return a new Violation object
     */
    @Deprecated // should really supply a message
    protected Violation createViolationForImport(SourceCode sourceCode, ImportNode importNode) {
        return createViolationForImport(sourceCode, importNode, null);  
    }

    /**
     * Create and return a new Violation for this rule and the specified import className and alias
     * @param sourceCode - the SourceCode
     * @param className - the class name (as specified within the import statement)
     * @param alias - the alias for the import statement
     * @param violationMessage - the violation message; may be null
     * @return a new Violation object
     */
    protected Violation createViolationForImport(SourceCode sourceCode, String className, String alias, String violationMessage) {
        Map importInfo = ImportUtil.sourceLineAndNumberForImport(sourceCode, className, alias);
        Violation violation = new Violation();
        violation.setRule(this);
        violation.setSourceLine((String) importInfo.get("sourceLine"));
        violation.setLineNumber((Integer) importInfo.get("lineNumber"));
        violation.setMessage(violationMessage);
        return violation;
    }

    private boolean shouldApplyThisRuleTo(SourceCode sourceCode) {
        if (!enabled) return false;
        if (!isReady()) return false;

        SourceCodeCriteria criteria = new SourceCodeCriteria();
        criteria.setApplyToFilesMatching(getApplyToFilesMatching());
        criteria.setDoNotApplyToFilesMatching(getDoNotApplyToFilesMatching());
        criteria.setApplyToFileNames(getApplyToFileNames());
        criteria.setDoNotApplyToFileNames(getDoNotApplyToFileNames());
        return criteria.matches(sourceCode);
    }

    private String getClassNameNoPackage() {
        String className = getClass().getName();
        int indexOfLastPeriod = className.lastIndexOf('.');
        return (indexOfLastPeriod == -1) ? className : className.substring(indexOfLastPeriod + 1);
    }

    /**
     * If the violationMessage property of this rule has been set, then use it to set the
     * message within each violation, overriding the original message(s), if any.
     */
    private void overrideViolationMessageIfNecessary(List<Violation> violations) {
        if (violationMessage != null && violations != null) {
            for (Violation violation : violations) {
                violation.setMessage(violationMessage);
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApplyToFilesMatching() {
        return applyToFilesMatching;
    }

    public void setApplyToFilesMatching(String applyToFilesMatching) {
        this.applyToFilesMatching = applyToFilesMatching;
    }

    public String getDoNotApplyToFilesMatching() {
        return doNotApplyToFilesMatching;
    }

    public void setDoNotApplyToFilesMatching(String doNotApplyToFilesMatching) {
        this.doNotApplyToFilesMatching = doNotApplyToFilesMatching;
    }

    public String getApplyToFileNames() {
        return applyToFileNames;
    }

    public void setApplyToFileNames(String applyToFileNames) {
        this.applyToFileNames = applyToFileNames;
    }

    public String getDoNotApplyToFileNames() {
        return doNotApplyToFileNames;
    }

    public void setDoNotApplyToFileNames(String doNotApplyToFileNames) {
        this.doNotApplyToFileNames = doNotApplyToFileNames;
    }

    public String getViolationMessage() {
        return violationMessage;
    }

    public void setViolationMessage(String violationMessage) {
        this.violationMessage = violationMessage;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}