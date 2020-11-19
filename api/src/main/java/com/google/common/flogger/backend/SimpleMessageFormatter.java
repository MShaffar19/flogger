/*
 * Copyright (C) 2017 The Flogger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.flogger.backend;

import com.google.common.flogger.LogContext;
import com.google.common.flogger.MetadataKey;
import com.google.common.flogger.MetadataKey.KeyValueHandler;
import java.util.Collections;
import java.util.Set;
import java.util.logging.Level;
import org.checkerframework.checker.nullness.compatqual.NullableDecl;

/**
 * Helper class for formatting LogData as text. This class is useful for any logging backend which
 * performs unstructured, text only, logging. Note however that it makes several assumptions
 * regarding metadata and formatting, which may not apply to every text based logging backend.
 *
 * <p>This primarily exists to support both the JDK logging classes and text only Android backends.
 * Code in here may be factored out as necessary to support other use cases in future.
 *
 * <p>If a text based logger backend is not performance sensitive, then it should just append the
 * log message and metadata to a local buffer using {@code appendFormatted(...)}. For example:
 *
 * <pre>{@code
 * StringBuilder buffer = new StringBuilder();
 * // Optional prefix goes here...
 * SimpleMessageFormatter.appendFormatted(logData, metadata, METADATA_HANDLER, buffer);
 * // Optional suffix goes here...
 * String message = buffer.toString();
 * }</pre>
 *
 * <p>If performance is an issue and no additional formatting is being done by the backend, then
 * {@link #mustBeFormatted(LogData, MetadataProcessor, Set)} can be used to help determine
 * situations in which it is safe to just use the literal log message without creating an additional
 * buffer.
 */
public final class SimpleMessageFormatter {
  @SuppressWarnings("ConstantCaseForConstants")
  private static final Set<MetadataKey<?>> DEFAULT_KEYS_TO_IGNORE =
      Collections.<MetadataKey<?>>singleton(LogContext.Key.LOG_CAUSE);

  private static final MetadataHandler<KeyValueHandler> DEFAULT_HANDLER =
      MetadataKeyValueHandlers.getDefaultHandler(DEFAULT_KEYS_TO_IGNORE);

  private static final LogMessageFormatter DEFAULT_FORMATTER =
      new LogMessageFormatter() {
        @Override
        public StringBuilder append(
            LogData logData, MetadataProcessor metadata, StringBuilder out) {
          return SimpleMessageFormatter.appendFormatted(logData, metadata, DEFAULT_HANDLER, out);
        }

        @Override
        public String format(LogData logData, MetadataProcessor metadata) {
          return SimpleMessageFormatter.format(logData, metadata);
        }
      };

  /**
   * Returns the singleton default log message formatter. This formats log messages in the form:
   *
   * <pre>{@code
   * Log message [CONTEXT key="value" id=42 ]
   * }</pre>
   *
   * <p>with context from the log data and scope, merged together in a sequence of key/value pairs
   * after the formatted message. If the log message is long or multi-line, then the context suffix
   * will be formatted on a single separate line.
   *
   * <p>Only the "cause" is omitted from the context section, since it's handled separately by most
   * logger backends and not considered part of the formatted message.
   */
  public static LogMessageFormatter getDefaultFormatter() {
    return DEFAULT_FORMATTER;
  }

  /**
   * Appends the formatted message and metadata to the given buffer using the supplied metadata
   * handler. A custom metadata handler is useful if the logger backend wishes to:
   *
   * <ul>
   *   <li>Ignore more than just the default set of metadata keys (currently just the "cause").
   *   <li>Intercept and capture metadata values for additional processing or logging control.
   * </ul>
   *
   * @param logData the log statement data.
   * @param metadata contextual metadata to be appended after the formatted message.
   * @param metadataHandler a metadata handler to handle metadata during formatting.
   * @param buffer destination buffer in to which the log message and metadata will be appended.
   * @return the given destination buffer (for method chaining).
   */
  // TODO: Inline this and remove (it's just not worth it for 2 lines of code).
  public static StringBuilder appendFormatted(
      LogData logData,
      MetadataProcessor metadata,
      MetadataHandler<KeyValueHandler> metadataHandler,
      StringBuilder buffer) {
    BaseMessageFormatter.appendFormattedMessage(logData, buffer);
    return appendContext(metadata, metadataHandler, buffer);
  }

  /**
   * Appends formatted context information to the given buffer using the supplied metadata handler.
   * A custom metadata handler is useful if the logger backend wishes to:
   *
   * <ul>
   *   <li>Ignore more than just the default set of metadata keys (currently just the "cause").
   *   <li>Intercept and capture metadata values for additional processing or logging control.
   * </ul>
   *
   * @param metadataProcessor snapshot of the metadata to be processed ({@link MetadataProcessor} is
   *     reusable so passing one in can save repeated processing of the same metadata).
   * @param metadataHandler a metadata handler for intercepting and dispatching metadata during
   *     formatting.
   * @param buffer destination buffer into which the log message and metadata will be appended.
   * @return the given destination buffer (for method chaining).
   */
  public static StringBuilder appendContext(
      MetadataProcessor metadataProcessor,
      MetadataHandler<KeyValueHandler> metadataHandler,
      StringBuilder buffer) {
    KeyValueFormatter kvf = new KeyValueFormatter("[CONTEXT ", " ]", buffer);
    metadataProcessor.process(metadataHandler, kvf);
    kvf.done();
    return buffer;
  }

  /**
   * Returns the single literal value as a string. This method must never be called if the log data
   * has arguments to be formatted.
   *
   * <p>This method is designed to be paired with {@link #mustBeFormatted(LogData, Metadata, Set)}
   * and can always be safely called if that method returned {@code false} for the same log data.
   *
   * @param logData the log statement data.
   * @return the single logged value as a string.
   * @throws IllegalStateException if the log data had arguments to be formatted (i.e. there was a
   *     template context).
   */
  public static String getLiteralLogMessage(LogData logData) {
    return MessageUtils.safeToString(logData.getLiteralArgument());
  }

  /**
   * An internal helper method for logger backends which are aggressively optimized for performance.
   * This method is a best-effort optimization and should not be necessary for most implementations.
   * It is not a stable API and may be removed at some point in the future.
   *
   * <p>This method attempts to determine, for the given log data and log metadata, if the default
   * message formatting performed by the other methods in this class would just result in the
   * literal log message being used, with no additional formatting.
   *
   * <p>If this method returns {@code false} then the literal log message can be obtained via {@link
   * #getLiteralLogMessage(LogData)}, otherwise {@code appendFormatted()} should be used instead.
   *
   * <p>By calling this class it is possible to more easily detect cases where using buffers to
   * format the log message is not required. Obviously a logger backend my have its own reasons for
   * needing buffering (e.g. prepending log site data) and those must also be taken into account.
   *
   * @param logData the log statement data.
   * @param metadata the metadata intended to be formatted with the log statement.
   * @param keysToIgnore a set of metadata keys which are known not to appear in the final formatted
   *     message.
   */
  public static boolean mustBeFormatted(
      LogData logData, MetadataProcessor metadata, Set<MetadataKey<?>> keysToIgnore) {
    // If there are logged arguments or more metadata keys than can be ignored, we fail immediately
    // which avoids the cost of creating the metadata key set (so don't remove the size check).
    return logData.getTemplateContext() != null
        || metadata.keyCount() > keysToIgnore.size()
        || !keysToIgnore.containsAll(metadata.keySet());
  }

  /**
   * Helper method to format the log message and any metadata with the default formatting. This is
   * exposed in a public API by the default log message formatter (but also used in deprecated
   * methods for now).
   */
  private static String format(LogData logData, MetadataProcessor metadata) {
    return mustBeFormatted(logData, metadata, DEFAULT_KEYS_TO_IGNORE)
        ? appendFormatted(logData, metadata, DEFAULT_HANDLER, new StringBuilder()).toString()
        : getLiteralLogMessage(logData);
  }

  // ---- Everything below this point is deprecated and will be removed. ----

  /** @deprecated Use a {@link LogMessageFormatter} and obtain the level and cause separately. */
  @Deprecated
  public static void format(LogData logData, SimpleLogHandler receiver) {
    MetadataProcessor metadata =
        MetadataProcessor.forScopeAndLogSite(Metadata.empty(), logData.getMetadata());
    receiver.handleFormattedLogMessage(
        logData.getLevel(),
        format(logData, metadata),
        metadata.getSingleValue(LogContext.Key.LOG_CAUSE));
  }

  /**
   * Note: If you were previously calling this with {@link Option#WITH_LOG_SITE} then you should
   * replace that call with the following code snippet:
   *
   * <pre>{@code
   * // Can be obtained from AbstractLogRecord.
   * MetadataProcessor metadata = ...;
   * StringBuilder buffer = new StringBuilder();
   * if (MessageUtils.appendLogSite(logData.getLogSite(), buffer)) {
   *   buffer.append(" ");
   * }
   * String message = SimpleMessageFormatter
   *     .getDefaultFormatter().appendFormatted(logData, metadata, buffer).toString();
   * Level logLevel = logData.getLevel();
   * Throwable cause = metadata.getSingleValue(LogContext.Key.LOG_CAUSE);
   * // Use log level, message and cause as before, but without needing a receiver ...
   * }</pre>
   *
   * @deprecated Prepending the log site is no longer directly supported by this class.
   */
  @Deprecated
  static void format(LogData logData, SimpleLogHandler receiver, Option option) {
    String message;
    switch (option) {
      case WITH_LOG_SITE:
        StringBuilder buffer = new StringBuilder();
        if (MessageUtils.appendLogSite(logData.getLogSite(), buffer)) {
          buffer.append(" ");
        }
        message =
            appendFormatted(
                    logData,
                    MetadataProcessor.forScopeAndLogSite(Metadata.empty(), logData.getMetadata()),
                    DEFAULT_HANDLER,
                    buffer)
                .toString();
        Throwable cause = logData.getMetadata().findValue(LogContext.Key.LOG_CAUSE);
        receiver.handleFormattedLogMessage(logData.getLevel(), message, cause);
        break;

      case DEFAULT:
        format(logData, receiver);
        break;
    }
  }

  /** @deprecated Use a {@link LogMessageFormatter} and obtain the level and cause separately. */
  @Deprecated
  public interface SimpleLogHandler {
    /**
     * Handles a single formatted log statement with the given level, message and "cause". This is
     * called back exactly once, from the same thread, for every call made to {@link #format}.
     */
    void handleFormattedLogMessage(Level level, String message, @NullableDecl Throwable thrown);
  }

  /**
   * @deprecated Use BaseMessageFormatter and MetadataProcessor/MetadataHandler/KeyValueFormatter to
   *     implement formatting according to your needs.
   */
  @Deprecated
  enum Option {
    // Default option.
    DEFAULT,
    // Prepend log site information in the form of [class].[methodName]:[lineNumber] [Message]
    WITH_LOG_SITE
  }

  private SimpleMessageFormatter() {}
}
