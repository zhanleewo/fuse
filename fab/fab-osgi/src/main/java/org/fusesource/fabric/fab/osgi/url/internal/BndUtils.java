/*
 * Copyright 2008 Alin Dreghiciu.
 * Copyright 2008 Peter Kriens.
 *
 * Licensed  under the  Apache License,  Version 2.0  (the "License");
 * you may not use  this file  except in  compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed  under the  License is distributed on an "AS IS" BASIS,
 * WITHOUT  WARRANTIES OR CONDITIONS  OF ANY KIND, either  express  or
 * implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fusesource.fabric.fab.osgi.url.internal;

import aQute.lib.osgi.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.fusesource.fabric.fab.util.Files;
import org.fusesource.fabric.fab.util.Strings;
import org.ops4j.lang.NullArgumentException;

import java.io.*;
import java.lang.reflect.Array;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.fusesource.fabric.fab.util.Strings.emptyIfNull;
import static org.fusesource.fabric.fab.util.Strings.notEmpty;

/**
 * Wrapper over PeterK's bnd lib.
 *
 * @author Alin Dreghiciu
 * @since 0.1.0, January 14, 2008
 */
public class BndUtils
{

    /**
     * Logger.
     */
    private static final Log LOG = LogFactory.getLog( BndUtils.class );

    /**
     * Regex pattern for matching instructions when specified in url.
     */
    private static final Pattern INSTRUCTIONS_PATTERN =
        Pattern.compile( "([a-zA-Z_0-9-]+)=([-!\"'()*+,.0-9A-Z_a-z%;:=/]+)" );

    private static final String ALLOWED_PACKAGE_CLAUSES = Strings.join(Arrays.asList(Constants.directives), ":,") + ":,version:";

    /**
     * Utility class. Ment to be used using static methods
     */
    private BndUtils()
    {
        // utility class
    }

    /**
     * Processes the input jar and generates the necessary OSGi headers using specified instructions.
     *
     * @param jarInputStream input stream for the jar to be processed. Cannot be null.
     * @param instructions   bnd specific processing instructions. Cannot be null.
     * @param jarInfo        information about the jar to be processed. Usually the jar url. Cannot be null or empty.
     *
     * @return an input stream for the generated bundle
     *
     * @throws NullArgumentException if any of the parameters is null
     * @throws IOException           re-thron during jar processing
     */
    public static InputStream createBundle( final InputStream jarInputStream,
                                            final Properties instructions,
                                            final String jarInfo )
        throws IOException
    {
        return createBundle( jarInputStream, instructions, jarInfo, OverwriteMode.KEEP, Collections.EMPTY_MAP, "", new HashSet<String>(), null );
    }

    /**
     * Processes the input jar and generates the necessary OSGi headers using specified instructions.
     *
     *
     * @param jarInputStream input stream for the jar to be processed. Cannot be null.
     * @param instructions   bnd specific processing instructions. Cannot be null.
     * @param jarInfo        information about the jar to be processed. Usually the jar url. Cannot be null or empty.
     * @param overwriteMode  manifets overwrite mode
     *
     * @param actualImports
     * @return an input stream for the generated bundle
     *
     * @throws NullArgumentException if any of the parameters is null
     * @throws IOException           re-thron during jar processing
     */
    public static InputStream createBundle(final InputStream jarInputStream,
                                           final Properties instructions,
                                           final String jarInfo,
                                           final OverwriteMode overwriteMode,
                                           final Map<String, Object> embeddedResources,
                                           final String extraImportPackages,
                                           final HashSet<String> actualImports,
                                           final VersionResolver versionResolver)
        throws IOException
    {
        NullArgumentException.validateNotNull( jarInputStream, "Jar URL" );
        NullArgumentException.validateNotNull( instructions, "Instructions" );
        NullArgumentException.validateNotEmpty( jarInfo, "Jar info" );

        LOG.debug( "Creating bundle for [" + jarInfo + "]" );
        LOG.debug( "Overwrite mode: " + overwriteMode );
        LOG.trace( "Using instructions " + instructions );

        final Jar jar = new Jar( "dot", jarInputStream );

        final Manifest manifest = jar.getManifest();

        // Make the jar a bundle if it is not already a bundle
        if( manifest == null
            || OverwriteMode.KEEP != overwriteMode
            || ( manifest.getMainAttributes().getValue( Analyzer.EXPORT_PACKAGE ) == null
                 && manifest.getMainAttributes().getValue( Analyzer.IMPORT_PACKAGE ) == null )
            )
        {
            // Do not use instructions as default for properties because it looks like BND uses the props
            // via some other means then getProperty() and so the instructions will not be used at all
            // So, just copy instructions to properties
            final Properties properties = new Properties();
            properties.putAll( instructions );

            properties.put( "Generated-By-Fabric-From", jarInfo );

            final Analyzer analyzer = new Analyzer();
            analyzer.setJar( jar );
            analyzer.setProperties( properties );

            // now lets add all the new embedded jars
            for (Map.Entry<String, Object> entry : embeddedResources.entrySet()) {
                String path = entry.getKey();
                Object value = entry.getValue();
                Resource resource = toResource(value);
                if (resource != null) {
                    jar.putResource(path, resource);
                    try {
                        File file = toFile(value);
                        analyzer.addClasspath(file);
                    } catch (IOException e) {
                        LOG.warn("Failed to get File for " + value + ". " + e, e);
                    }
                }
            }


            if( manifest != null && OverwriteMode.MERGE == overwriteMode )
            {
                analyzer.mergeManifest( manifest );
            }
            checkMandatoryProperties( analyzer, jar, jarInfo );

            analyzer.calcManifest();

            Attributes main = jar.getManifest().getMainAttributes();
            String importPackages = emptyIfNull(main.getValue(Analyzer.IMPORT_PACKAGE));

            Map<String, Map<String, String>> values = new Analyzer().parseHeader(importPackages);

            if (notEmpty(extraImportPackages) ) {
                Map<String, Map<String, String>> extra = new Analyzer().parseHeader(extraImportPackages);

                // Merge in the extra imports.
                for (Map.Entry<String, Map<String, String>> entry : extra.entrySet()) {
                    Map<String, String> original = values.get(entry.getKey());
                    if( original == null ) {
                        original = entry.getValue();
                    } else {
                        original.putAll(entry.getValue());
                    }
                    values.put(entry.getKey(), original);
                }
            }

            // add any missing version clauses
            if (versionResolver != null) {
                for (Map.Entry<String, Map<String, String>> entry : values.entrySet()) {
                    String packageName = entry.getKey();
                    Map<String, String> packageValues = entry.getValue();
                    if (!packageValues.containsKey("version")) {
                        String version = versionResolver.resolvePackage(packageName);
                        if (version != null) {
                            packageValues.put("version", version);
                        }
                    }
                }
            }
            // TODO do we really need to filter out any of the attribute values?
            // we were filtering out everything bar resolution:
            //importPackages  = Processor.printClauses(values, "resolution:");
            importPackages  = Processor.printClauses(values, ALLOWED_PACKAGE_CLAUSES);

            for (Map.Entry<String, Map<String, String>> entry : values.entrySet()) {
                String res = entry.getValue().get("resolution:");
                if( !"optional".equals(res) ) {
                    // add all the non-optional deps..
                    actualImports.add(entry.getKey());
                }
            }

            if (notEmpty(importPackages)) {
                main.putValue(Analyzer.IMPORT_PACKAGE, importPackages);
            }
        }
        return createInputStream( jar );
    }

    private static File toFile(Object value) throws IOException {
        if (value instanceof File) {
            return (File) value;
        } else if (value instanceof URL) {
            return Files.urlToFile((URL) value, "fabric-analyser-jar-", ".jar");
        } else {
            throw new IllegalArgumentException("Cannot convert value " + value + " into a Resource. Expected File or URL");
        }
    }

    protected static Resource toResource(Object value) {
        if (value instanceof File) {
            return new FileResource((File) value);
        } else if (value instanceof URL) {
            return new URLResource((URL) value);
        } else {
            throw new IllegalArgumentException("Cannot convert value " + value + " into a Resource. Expected File or URL");
        }
    }

    /**
     * Creates an piped input stream for the wrapped jar.
     * This is done in a thread so we can return quickly.
     *
     * @param jar the wrapped jar
     *
     * @return an input stream for the wrapped jar
     *
     * @throws java.io.IOException re-thrown
     */
    private static PipedInputStream createInputStream( final Jar jar )
        throws IOException
    {
        final PipedInputStream pin = new PipedInputStream();
        final PipedOutputStream pout = new PipedOutputStream( pin );

        new Thread()
        {
            public void run()
            {
                try
                {
                    jar.write( pout );
                }
                catch( IOException e )
                {
                    LOG.warn( "Bundle cannot be generated" );
                }
                finally
                {
                    try
                    {
                        jar.close();
                        pout.close();
                    }
                    catch( IOException ignore )
                    {
                        // if we get here something is very wrong
                        LOG.error( "Bundle cannot be generated", ignore );
                    }
                }
            }
        }.start();

        return pin;
    }

    /**
     * Check if manadatory properties are present, otherwise generate default.
     *
     * @param analyzer     bnd analyzer
     * @param jar          bnd jar
     * @param symbolicName bundle symbolic name
     */
    private static void checkMandatoryProperties( final Analyzer analyzer,
                                                  final Jar jar,
                                                  final String symbolicName )
    {
        final String importPackage = analyzer.getProperty( Analyzer.IMPORT_PACKAGE );
        if( importPackage == null || importPackage.trim().length() == 0 )
        {
            analyzer.setProperty( Analyzer.IMPORT_PACKAGE, "*;resolution:=optional" );
        }
        final String exportPackage = analyzer.getProperty( Analyzer.EXPORT_PACKAGE );
        if( exportPackage == null || exportPackage.trim().length() == 0 )
        {
            analyzer.setProperty( Analyzer.EXPORT_PACKAGE, analyzer.calculateExportsFromContents( jar ) );
        }
        final String localSymbolicName = analyzer.getProperty( Analyzer.BUNDLE_SYMBOLICNAME, symbolicName );
        analyzer.setProperty( Analyzer.BUNDLE_SYMBOLICNAME, generateSymbolicName( localSymbolicName ) );
    }

    /**
     * Processes symbolic name and replaces osgi spec invalid characters with "_".
     *
     * @param symbolicName bundle symbolic name
     *
     * @return a valid symbolic name
     */
    private static String generateSymbolicName( final String symbolicName )
    {
        return symbolicName.replaceAll( "[^a-zA-Z_0-9.-]", "_" );
    }

    /**
     * Parses bnd instructions out of an url query string.
     *
     * @param query query part of an url.
     *
     * @return parsed instructions as properties
     *
     * @throws java.net.MalformedURLException if provided path does not comply to syntax.
     */
    public static Properties parseInstructions( final String query )
        throws MalformedURLException
    {
        final Properties instructions = new Properties();
        if( query != null )
        {
            try
            {
                // just ignore for the moment and try out if we have valid properties separated by "&"
                final String segments[] = query.split( "&" );
                for( String segment : segments )
                {
                    // do not parse empty strings
                    if( segment.trim().length() > 0 )
                    {
                        final Matcher matcher = INSTRUCTIONS_PATTERN.matcher( segment );
                        if( matcher.matches() )
                        {
                            instructions.setProperty(
                                matcher.group( 1 ),
                                URLDecoder.decode( matcher.group( 2 ), "UTF-8" )
                            );
                        }
                        else
                        {
                            throw new MalformedURLException( "Invalid syntax for instruction [" + segment
                                                             + "]. Take a look at http://www.aqute.biz/Code/Bnd."
                            );
                        }
                    }
                }
            }
            catch( UnsupportedEncodingException e )
            {
                // thrown by URLDecoder but it should never happen
                throwAsMalformedURLException( "Could not retrieve the instructions from [" + query + "]", e );
            }
        }
        return instructions;
    }

    /**
     * Creates an MalformedURLException with a message and a cause.
     *
     * @param message exception message
     * @param cause   exception cause
     *
     * @throws MalformedURLException the created MalformedURLException
     */
    private static void throwAsMalformedURLException( final String message, final Exception cause )
        throws MalformedURLException
    {
        final MalformedURLException exception = new MalformedURLException( message );
        exception.initCause( cause );
        throw exception;
    }

}