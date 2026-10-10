package io.ohmvir.plugins.jenkinsocr;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Job;
import hudson.model.ParametersDefinitionProperty;
import java.util.Collection;
import java.util.Collections;
import jenkins.model.Jenkins;
import jenkins.model.TransientActionFactory;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Disables Declarative Pipeline's "Restart from Stage" for jobs that take an {@link ImageParameterDefinition}: the
 * uploaded image is deleted when the original build finishes, so a restarted build would have no image to work with.
 * Same effect as adding {@code options { disableRestartFromStage() }} to the Jenkinsfile.
 *
 * <p>The action is looked up reflectively so that this plugin does not depend on Declarative Pipeline.
 */
@Extension
@SuppressWarnings({"rawtypes", "unchecked"})
public class DisableRestartFromStageForImageParameters extends TransientActionFactory<Job> {

    private static final String DISABLE_RESTART_ACTION_CLASS =
            "org.jenkinsci.plugins.pipeline.modeldefinition.actions.DisableRestartFromStageAction";

    @Override
    public Class<Job> type() {
        return Job.class;
    }

    @Override
    public @NonNull Collection<? extends Action> createFor(@NonNull Job target) {
        ParametersDefinitionProperty property =
                (ParametersDefinitionProperty) target.getProperty(ParametersDefinitionProperty.class);
        if (property == null
                || property.getParameterDefinitions().stream().noneMatch(ImageParameterDefinition.class::isInstance)) {
            return Collections.emptySet();
        }
        Action action = createDisableRestartAction();
        return action == null ? Collections.emptySet() : Collections.singleton(action);
    }

    private static @Nullable Action createDisableRestartAction() {
        try {
            Class<?> actionClass = Class.forName(
                    DISABLE_RESTART_ACTION_CLASS, true, Jenkins.get().getPluginManager().uberClassLoader);
            return (Action) actionClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            // Declarative Pipeline is not installed, so there is no "Restart from Stage" to disable
            return null;
        }
    }
}
