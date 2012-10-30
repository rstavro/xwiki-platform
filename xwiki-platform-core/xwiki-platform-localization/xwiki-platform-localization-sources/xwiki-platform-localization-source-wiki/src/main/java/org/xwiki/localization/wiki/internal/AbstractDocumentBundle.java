/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.localization.wiki.internal;

import java.io.StringReader;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import javax.inject.Inject;
import javax.inject.Provider;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.LoggerFactory;
import org.xwiki.bridge.event.DocumentUpdatedEvent;
import org.xwiki.cache.DisposableCacheValue;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.component.phase.Disposable;
import org.xwiki.component.util.DefaultParameterizedType;
import org.xwiki.localization.Bundle;
import org.xwiki.localization.BundleContext;
import org.xwiki.localization.internal.AbstractCachedBundle;
import org.xwiki.localization.internal.DefaultLocalizedBundle;
import org.xwiki.localization.internal.DefaultTranslation;
import org.xwiki.localization.internal.LocalizedBundle;
import org.xwiki.localization.message.TranslationMessage;
import org.xwiki.localization.message.TranslationMessageParser;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.observation.EventListener;
import org.xwiki.observation.ObservationManager;
import org.xwiki.observation.event.Event;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;

/**
 * Base class for {@link Bundle}s using wiki documents as resources. Provides methods for loading properties from
 * documents, watching loaded documents and invalidating cached translations.
 * 
 * @version $Id$
 * @since 4.3M2
 */
public class AbstractDocumentBundle extends AbstractCachedBundle implements Bundle, DisposableCacheValue, Disposable
{
    /**
     * The prefix to use in all wiki document based translations.
     */
    public final static String ID_PREFIX = "document:";

    @Inject
    protected BundleContext bundleContext;

    @Inject
    protected EntityReferenceSerializer<String> serializer;

    @Inject
    protected Provider<XWikiContext> contextProvider;

    @Inject
    private ObservationManager observation;

    protected TranslationMessageParser translationMessageParser;

    protected List<Event> events;

    private EventListener listener = new EventListener()
    {
        @Override
        public void onEvent(Event arg0, Object arg1, Object arg2)
        {
            XWikiDocument document = (XWikiDocument) arg1;

            bundleCache.remove(document.getLanguage());
        }

        @Override
        public String getName()
        {
            return "localization.bundle." + getId();
        }

        @Override
        public List<Event> getEvents()
        {
            return events;
        }
    };

    protected DocumentReference documentReference;

    public AbstractDocumentBundle(DocumentReference reference, ComponentManager componentManager,
        TranslationMessageParser translationMessageParser) throws ComponentLookupException
    {
        this.bundleContext = componentManager.getInstance(BundleContext.class);
        this.serializer = componentManager.getInstance(EntityReferenceSerializer.TYPE_STRING);
        this.contextProvider =
            componentManager.getInstance(new DefaultParameterizedType(null, Provider.class,
                new Type[] {XWikiContext.class}));
        this.observation = componentManager.getInstance(ObservationManager.class);

        this.translationMessageParser = translationMessageParser;

        this.logger = LoggerFactory.getLogger(getClass());

        setReference(reference);

        initialize();
    }

    private void initialize()
    {
        this.events = Arrays.<Event> asList(new DocumentUpdatedEvent(this.documentReference));

        this.observation.addListener(this.listener);
    }

    protected void setReference(DocumentReference reference)
    {
        this.documentReference = reference;

        setId(ID_PREFIX + this.serializer.serialize(reference));
    }

    protected LocalizedBundle loadDocumentLocaleBundle(Locale locale) throws Exception
    {
        XWikiContext context = this.contextProvider.get();

        XWikiDocument document = context.getWiki().getDocument(this.documentReference, context);

        String localeString = locale.toString();
        if (StringUtils.isNotEmpty(localeString)) {
            XWikiDocument tdocument = document.getTranslatedDocument(localeString, context);

            if (tdocument == document) {
                // No document found for this locale
                return null;
            }

            document = tdocument;
        }

        String content = document.getContent();

        Properties properties = new Properties();
        properties.load(new StringReader(content));

        // Convert to LocalBundle
        DefaultLocalizedBundle localeBundle = new DefaultLocalizedBundle(this, locale);

        TranslationMessageParser parser = getTranslationMessageParser();

        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            if (entry.getKey() instanceof String && entry.getValue() instanceof String) {
                String key = (String) entry.getKey();
                String message = (String) entry.getValue();

                TranslationMessage translationMessage = parser.parse(message);

                localeBundle.addTranslation(new DefaultTranslation(this.bundleContext, localeBundle, key,
                    translationMessage));
            }
        }

        return localeBundle;
    }

    protected TranslationMessageParser getTranslationMessageParser()
    {
        return this.translationMessageParser;
    }

    @Override
    protected LocalizedBundle createBundle(Locale locale)
    {
        LocalizedBundle localeBundle;
        try {
            localeBundle = loadDocumentLocaleBundle(locale);
        } catch (Exception e) {
            this.logger.error("Failed to get localization bundle", e);

            localeBundle = null;
        }

        return localeBundle;
    }

    @Override
    public void dispose()
    {
        this.observation.removeListener(this.listener.getName());
    }
}
