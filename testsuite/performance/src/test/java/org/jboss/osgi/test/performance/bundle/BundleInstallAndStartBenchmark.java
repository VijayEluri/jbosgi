/*
 * JBoss, Home of Professional Open Source
 * Copyright 2005, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
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
package org.jboss.osgi.test.performance.bundle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Date;

import org.jboss.osgi.test.common.CommonClass;
import org.jboss.osgi.test.performance.AbstractThreadedBenchmark;
import org.jboss.osgi.test.performance.ChartType;
import org.jboss.osgi.test.performance.ChartTypeImpl;
import org.jboss.osgi.test.util1.Util1;
import org.jboss.osgi.test.util2.Util2;
import org.jboss.osgi.test.util3.Util3;
import org.jboss.osgi.test.util4.Util4;
import org.jboss.osgi.test.util5.Util5;
import org.jboss.osgi.test.versioned.VersionedInterface;
import org.jboss.osgi.test.versioned.impl.VersionedClass;
import org.jboss.osgi.testing.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

/**
 * This Benchmark tests how much time it takes to install and start bundles in a number of class-spaces.
 * It creates the following configuration:
 * 
 * 5 (Versioned) Common Bundles
 *   - Exports org.jboss.osgi.test.common;version=x
 * 5 Numbered (but not versioned) Util Bundles
 *   - Imports org.jboss.osgi.test.common
 *   - Exports org.jboss.osgi.test.util[x];uses="org.jboss.osgi.test.common"
 * 5 Versioned Interfaces Bundles
 *   - Exports org.jboss.osgi.test.versioned;version=x
 * 5 Versioned Impl Bundles
 *   - Imports org.jboss.osgi.test.common;version=[x,x]
 *   - Imports org.jboss.osgi.test.versioned;version=[x,x]
 *   - Imports org.jboss.osgi.test.util[x]
 *   - Exports org.jboss.osgi.test.versioned.impl;version=x;uses=org.jboss.osgi.test.util[x]
 * a large number of test bundles
 *   - Imports org.jboss.osgi.test.common;version=[x,x]
 *   - Imports org.jboss.osgi.test.versioned;version=[x,x]
 *   - Imports org.jboss.osgi.test.versioned.impl;version=[x,x]
 *   
 * Each test bundle loads a class of each of its 3 dependency packages in its activator. This also triggers
 * an indirect load on the Util[x] class.
 * 
 * Time is measured for installing and activating of all the bundles.
 * 
 * The original intention was to make the Common Bundles all of the same version, but this caused issues
 * with the jbossmc resolver. 
 * 
 * @author <a href="david@redhat.com">David Bosschaert</a>
 */
public class BundleInstallAndStartBenchmark extends AbstractThreadedBenchmark<Integer>
{
   private static final ChartType INSTALL_START = new ChartTypeImpl("IS", "Bundle Install and Start Time", "Number of Bundles", "Time (ms)");
   private final File bundleStorage;

   protected BundleInstallAndStartBenchmark(BundleContext bc)
   {
      super(bc);
      bundleStorage = new File(tempDir, "bundles");
      bundleStorage.mkdirs();
   }
   
   @Override
   protected ChartType[] getAllChartTypes()
   {
      return new ChartType[] { INSTALL_START };
   }

   public void run(int numThreads, int numBundlesPerThread) throws Exception
   {
      prepareTest(numThreads, numBundlesPerThread);
      runTest(numThreads, numBundlesPerThread);
   }

   void prepareTest(int numThreads, int numBundlesPerThread) throws Exception
   {
      installBaseLineBundles();

      for (String threadName : getThreadNames(numThreads))
         createTestBundles(threadName, numBundlesPerThread);
   }

   private void installBaseLineBundles() throws Exception
   {
      for (int i = 1; i <= 5; i++)
      {
         bundleContext.installBundle("common" + i, getCommonBundle("" + i));
         bundleContext.installBundle("util" + i, getUtilBundle(i));
         bundleContext.installBundle("versioned-intf" + i, getVersionedIntfBundle("" + i));
         bundleContext.installBundle("versioned-impl" + i, getVersionedImplBundle(i));
      }
   }

   private void createTestBundles(String threadName, int numBundlesPerThread) throws Exception
   {
      System.out.println("Creating test bundles in " + bundleStorage);
      for (int i = 0; i < numBundlesPerThread; i++)
      {
         InputStream is = getTestBundle(threadName, i);
         FileOutputStream fos = new FileOutputStream(new File(bundleStorage, threadName + i + ".jar"));
         try
         {
            pumpStreams(is, fos);
         }
         finally
         {
            fos.close();
            is.close();
         }
      }
   }

   @Override
   protected void runThread(String threadName, Integer numBundlesPerThread) throws Exception
   {
      System.out.println("Starting at " + new Date());
      long start = System.currentTimeMillis();
      for (int i = 0; i < numBundlesPerThread; i++)
      {
         URI uri = new File(bundleStorage, threadName + i + ".jar").toURI();
         Bundle bundle = bundleContext.installBundle(uri.toString());
         bundle.start();
      }

      // Wait until all bundles have been started
      int numStartedBundles = 0;
      while (numStartedBundles < numBundlesPerThread)
      {
         Thread.sleep(200);
         synchronized (threadName.intern())
         {
            numStartedBundles = Integer.parseInt(System.getProperty(threadName + "started-bundles", "0"));
         }
      }
      long end = System.currentTimeMillis();
      writeData(INSTALL_START, numBundlesPerThread, end - start);
      System.out.println("Installed Bundles " + new Date());
   }

   private InputStream getCommonBundle(final String version) throws Exception
   {
      final JavaArchive jar = ShrinkWrap.create(JavaArchive.class, "common-bundle-" + version);
      jar.setManifest(new Asset()
      {
         @Override
         public InputStream openStream()
         {
            OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
            builder.addBundleSymbolicName("CommonBundle" + version);
            builder.addBundleManifestVersion(2);
            builder.addImportPackages("org.osgi.framework");
            builder.addImportPackages(CommonClass.class.getPackage().getName() + ";version=\"[" + version + ".0," + version + ".0]\"");
            builder.addExportPackages(CommonClass.class.getPackage().getName() + ";version=\"" + version + ".0\"");
            return builder.openStream();
         }
      });
      jar.addClasses(CommonClass.class);
      return getInputStream(jar);
   }

   private InputStream getUtilBundle(final int i) throws Exception
   {
      final Class<?> utilClass = getUtilClass(i);
      
      final JavaArchive jar = ShrinkWrap.create(JavaArchive.class, "util_" + i);
      jar.setManifest(new Asset()
      {
         @Override
         public InputStream openStream()
         {
            OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
            builder.addBundleSymbolicName("Util" + i);
            builder.addBundleManifestVersion(2);
            builder.addImportPackages("org.osgi.framework");
            builder.addImportPackages(CommonClass.class.getPackage().getName());
            builder.addExportPackages(utilClass.getPackage().getName() + ";uses:=\"" + CommonClass.class.getPackage().getName() + "\"");
            return builder.openStream();
         }
      });
      jar.addClasses(utilClass);
      return getInputStream(jar);
   }

   private Class<?> getUtilClass(final int i)
   {
      switch (i)
      {
         case 1: 
            return Util1.class;
         case 2:
            return Util2.class;
         case 3:
            return Util3.class;
         case 4: 
            return Util4.class;
         case 5:
            return Util5.class;
      }
      return null;
   }

   private InputStream getVersionedIntfBundle(final String version) throws Exception
   {
      final JavaArchive jar = ShrinkWrap.create(JavaArchive.class, "versioned-intf-bundle-" + version);
      jar.setManifest(new Asset()
      {
         @Override
         public InputStream openStream()
         {
            OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
            builder.addBundleSymbolicName("VersionedIntfBundle" + version);
            builder.addBundleManifestVersion(2);
            builder.addImportPackages("org.osgi.framework");
            builder.addExportPackages(VersionedInterface.class.getPackage().getName() + ";version=\"" + version + ".0\"");
            return builder.openStream();
         }
      });
      jar.addClasses(VersionedInterface.class);
      return getInputStream(jar);
   }

   private InputStream getVersionedImplBundle(final int version) throws Exception
   {
      final JavaArchive jar = ShrinkWrap.create(JavaArchive.class, "versioned-impl-bundle-" + version);
      jar.setManifest(new Asset()
      {
         @Override
         public InputStream openStream()
         {
            Class<?> utilClass = getUtilClass(version);

            OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
            builder.addBundleSymbolicName("VersionedImplBundle" + version);
            builder.addBundleManifestVersion(2);
            builder.addImportPackages("org.osgi.framework");
            builder.addImportPackages(CommonClass.class.getPackage().getName() + ";version=\"[" + version + ".0," + version + ".0]\"");
            builder.addImportPackages(VersionedInterface.class.getPackage().getName() + ";version=\"[" + version + ".0," + version + ".0]\"");
            builder.addImportPackages(utilClass.getPackage().getName());
            builder.addExportPackages(VersionedClass.class.getPackage().getName() + ";version=\"" + version + ".0\";uses:=\""
                  + utilClass.getPackage().getName() + "\"");
            return builder.openStream();
         }
      });
      jar.addClasses(VersionedClass.class);
      return getInputStream(jar);
   }

   private InputStream getTestBundle(final String threadName, final int counter) throws Exception
   {
      final JavaArchive jar = ShrinkWrap.create(JavaArchive.class, "test-" + threadName + counter + ".jar");
      jar.setManifest(new Asset()
      {
         @Override
         public InputStream openStream()
         {
            OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
            builder.addBundleSymbolicName(getBSN(threadName, counter));
            builder.addBundleActivator(BundlePerfTestActivator.class.getName());
            builder.addBundleManifestVersion(2);
            builder.addImportPackages("org.osgi.framework");

            int ver = (counter % 5) + 1;
            builder.addImportPackages(CommonClass.class.getPackage().getName() + ";version=\"[" + ver + ".0," + ver + ".0]\"");
            builder.addImportPackages(VersionedInterface.class.getPackage().getName() + ";version=\"[" + ver + ".0," + ver + ".0]\"");
            builder.addImportPackages(VersionedClass.class.getPackage().getName() + ";version=\"[" + ver + ".0," + ver + ".0]\"");
            return builder.openStream();
         }
      });
      jar.addClasses(BundlePerfTestActivator.class);

      return getInputStream(jar);
   }

   private InputStream getInputStream(final JavaArchive jar) throws Exception
   {
      return jar.as(ZipExporter.class).exportZip();
   }

   private static String getBSN(String threadName, int counter)
   {
      return "Bundle-" + threadName + "-" + counter;
   }

   public static void pumpStreams(InputStream is, OutputStream os) throws IOException
   {
      byte[] bytes = new byte[8192];

      int length = 0;
      int offset = 0;

      while ((length = is.read(bytes, offset, bytes.length - offset)) != -1)
      {
         offset += length;

         if (offset == bytes.length)
         {
            os.write(bytes, 0, bytes.length);
            offset = 0;
         }
      }
      if (offset != 0)
      {
         os.write(bytes, 0, offset);
      }
   }
}
