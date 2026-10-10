package io.ohmvir.plugins.jenkinsocr;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Job;
import hudson.model.ParametersDefinitionProperty;
import java.util.Collection;
import java.util.Collections;
import jenkins.model.TransientActionFactory;
import org.jenkinsci.plugins.pipeline.modeldefinition.actions.DisableRestartFromStageAction;
import org.jspecify.annotations.NonNull;

/**
 * Disables Declarative Pipeline's "Restart from Stage" for jobs that take an {@link ImageParameterDefinition}: the
 * uploaded image is deleted when the original build finishes, so a restarted build would have no image to work with.
 * Same effect as adding {@code options { disableRestartFromStage() }} to the Jenkinsfile.
 */
@Extension(optional = true)
@SuppressWarnings({"rawtypes", "unchecked"})
public class DisableRestartFromStageForImageParameters extends TransientActionFactory<Job> {

    @Override
    public Class<Job> type() {
        return Job.class;
    }

    @Override
    public Class<? extends Action> actionType() {
        return DisableRestartFromStageAction.class;
    }

    @Override
    public @NonNull Collection<? extends Action> createFor(@NonNull Job target) {
        ParametersDefinitionProperty property =
                (ParametersDefinitionProperty) target.getProperty(ParametersDefinitionProperty.class);
        if (property == null
                || property.getParameterDefinitions().stream().noneMatch(ImageParameterDefinition.class::isInstance)) {
            return Collections.emptySet();
        }
        return Collections.singleton(new DisableRestartFromStageAction());
    }
}
