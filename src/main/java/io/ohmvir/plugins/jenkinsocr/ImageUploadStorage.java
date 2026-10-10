package io.ohmvir.plugins.jenkinsocr;

import hudson.Extension;
import hudson.Util;
import hudson.model.Action;
import hudson.model.AsyncPeriodicWork;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.model.queue.QueueListener;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Stores images uploaded through {@link ImageParameterDefinition} on disk under {@code $JENKINS_HOME} so that they
 * survive a Jenkins restart, and deletes them as soon as the build that received them finishes.
 *
 * <p>Each upload lives in its own directory, {@code $JENKINS_HOME/jenkinsocr-image-uploads/<id>/}, holding the image
 * itself and, once a build has started with it, an owner file naming that build. An upload belongs to exactly one
 * build: attempts to schedule another build with an upload that is already owned or already deleted (replay,
 * rebuild, ...) are refused.
 */
public final class ImageUploadStorage {

    private static final Logger LOGGER = Logger.getLogger(ImageUploadStorage.class.getName());

    private static final String ROOT_DIR_NAME = "jenkinsocr-image-uploads";
    private static final String IMAGE_FILE_NAME = "image";
    private static final String OWNER_FILE_NAME = "owner";
    private static final Pattern ID_PATTERN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** How long an upload that no queue item or build refers to is kept before the cleanup task deletes it. */
    private static final long ORPHAN_GRACE_PERIOD_MILLIS = 60L * 60 * 1000;

    private ImageUploadStorage() {}

    static File getRootDir() {
        return new File(Jenkins.get().getRootDir(), ROOT_DIR_NAME);
    }

    private static File getUploadDir(@NonNull String id) {
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid image upload id: " + id);
        }
        return new File(getRootDir(), id);
    }

    /**
     * Copies an uploaded image to disk.
     *
     * @return the id of the stored upload
     */
    static String store(@NonNull InputStream image) throws IOException {
        String id = UUID.randomUUID().toString();
        File dir = getUploadDir(id);
        Files.createDirectories(dir.toPath());
        try {
            Files.copy(image, new File(dir, IMAGE_FILE_NAME).toPath());
        } catch (IOException e) {
            delete(id);
            throw e;
        }
        return id;
    }

    static File getImageFile(@NonNull String id) {
        return new File(getUploadDir(id), IMAGE_FILE_NAME);
    }

    static boolean exists(@NonNull String id) {
        return getImageFile(id).isFile();
    }

    /** @return the externalizable id of the build that owns this upload, or {@code null} if no build owns it yet */
    static @Nullable String getOwner(@NonNull String id) {
        File ownerFile = new File(getUploadDir(id), OWNER_FILE_NAME);
        if (!ownerFile.isFile()) {
            return null;
        }
        try {
            return Files.readString(ownerFile.toPath(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to read owner of image upload " + id, e);
            return null;
        }
    }

    /** Records that {@code run} owns this upload, unless another build already owns it. */
    static synchronized void claim(@NonNull String id, @NonNull Run<?, ?> run) throws IOException {
        if (!exists(id)) {
            LOGGER.log(Level.WARNING, "Image upload {0} used by {1} no longer exists", new Object[] {id, run});
            return;
        }
        String owner = getOwner(id);
        if (owner != null) {
            if (!owner.equals(run.getExternalizableId())) {
                LOGGER.log(Level.WARNING, "Image upload {0} used by {1} is already owned by {2}", new Object[] {
                    id, run, owner
                });
            }
            return;
        }
        Files.writeString(
                new File(getUploadDir(id), OWNER_FILE_NAME).toPath(),
                run.getExternalizableId(),
                StandardCharsets.UTF_8);
    }

    static synchronized void delete(@NonNull String id) {
        File dir = getUploadDir(id);
        if (!dir.exists()) {
            return;
        }
        try {
            Util.deleteRecursive(dir);
            LOGGER.log(Level.FINE, "Deleted image upload {0}", id);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to delete image upload " + id, e);
        }
    }

    /** Deletes the upload if it is owned by {@code run} or by no build at all. */
    private static synchronized void release(@NonNull String id, @NonNull Run<?, ?> run) {
        String owner = getOwner(id);
        if (owner == null || owner.equals(run.getExternalizableId())) {
            delete(id);
        }
    }

    private static List<String> uploadIdsIn(@NonNull List<? extends Action> actions) {
        List<String> ids = new ArrayList<>();
        for (Action action : actions) {
            if (action instanceof ParametersAction parametersAction) {
                for (ParameterValue value : parametersAction.getAllParameters()) {
                    if (value instanceof ImageParameterValue imageValue && imageValue.getUploadId() != null) {
                        ids.add(imageValue.getUploadId());
                    }
                }
            }
        }
        return ids;
    }

    /** Ties uploads to the build using them and deletes them once that build has finished. */
    @Extension
    public static class RunListenerImpl extends RunListener<Run<?, ?>> {
        @Override
        public void onInitialize(Run<?, ?> run) {
            for (String id : uploadIdsIn(run.getAllActions())) {
                try {
                    claim(id, run);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to record " + run + " as owner of image upload " + id, e);
                }
            }
        }

        @Override
        public void onFinalized(Run<?, ?> run) {
            for (String id : uploadIdsIn(run.getAllActions())) {
                release(id, run);
            }
        }

        @Override
        public void onDeleted(Run<?, ?> run) {
            for (String id : uploadIdsIn(run.getAllActions())) {
                release(id, run);
            }
        }
    }

    /** Deletes the uploads of queue items that are cancelled before they start. */
    @Extension
    public static class CancelledQueueListener extends QueueListener {
        @Override
        public void onLeft(Queue.LeftItem li) {
            if (!li.isCancelled()) {
                return;
            }
            for (String id : uploadIdsIn(li.getAllActions())) {
                if (getOwner(id) == null) {
                    delete(id);
                }
            }
        }
    }

    /**
     * Refuses to schedule a build whose image upload is gone or belongs to another build, which happens when a
     * build is replayed or rebuilt with the parameters of an earlier build.
     */
    @Extension
    public static class ReuseGuard extends Queue.QueueDecisionHandler {
        @Override
        public boolean shouldSchedule(Queue.Task p, List<Action> actions) {
            for (String id : uploadIdsIn(actions)) {
                if (!exists(id) || getOwner(id) != null) {
                    LOGGER.log(
                            Level.WARNING,
                            "Refusing to schedule {0}: its uploaded image belongs to an earlier build and is no longer"
                                    + " available. Start a new build and upload the image again.",
                            p.getFullDisplayName());
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Safety net for uploads whose build will never delete them: uploads whose owning build has finished or was
     * removed, and uploads that never made it into a build (e.g. the queue item could not be scheduled).
     */
    @Extension
    public static class OrphanCleanup extends AsyncPeriodicWork {
        public OrphanCleanup() {
            super("Jenkins OCR image upload cleanup");
        }

        @Override
        public long getRecurrencePeriod() {
            return HOUR;
        }

        @Override
        public long getInitialDelay() {
            return 5 * MIN;
        }

        @Override
        protected void execute(TaskListener listener) {
            File[] dirs = getRootDir().listFiles(File::isDirectory);
            if (dirs == null) {
                return;
            }
            Set<String> queued = new HashSet<>();
            for (Queue.Item item : Queue.getInstance().getItems()) {
                queued.addAll(uploadIdsIn(item.getAllActions()));
            }
            for (File dir : dirs) {
                String id = dir.getName();
                if (!ID_PATTERN.matcher(id).matches() || queued.contains(id)) {
                    continue;
                }
                if (isOrphan(id, dir.toPath())) {
                    listener.getLogger().println("Deleting orphaned image upload " + id);
                    delete(id);
                }
            }
        }

        private static boolean isOrphan(String id, Path dir) {
            String owner = getOwner(id);
            if (owner != null) {
                Run<?, ?> run;
                try {
                    run = Run.fromExternalizableId(owner);
                } catch (IllegalArgumentException e) {
                    return true;
                }
                // isLogUpdated rather than isBuilding: a pipeline resumed after a restart only reports the former
                return run == null || !run.isLogUpdated();
            }
            try {
                long age = System.currentTimeMillis()
                        - Files.getLastModifiedTime(dir).toMillis();
                return age > ORPHAN_GRACE_PERIOD_MILLIS;
            } catch (IOException e) {
                return false;
            }
        }
    }
}
