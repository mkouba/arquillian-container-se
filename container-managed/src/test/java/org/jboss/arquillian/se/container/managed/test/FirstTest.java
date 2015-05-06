package org.jboss.arquillian.se.container.managed.test;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.testng.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.testng.Assert;
import org.testng.annotations.Test;

public class FirstTest extends Arquillian {

    @Deployment
    public static JavaArchive deploy() {
        return ShrinkWrap.create(JavaArchive.class).addClasses(FirstTest.class);
    }

    @Test
    public void firstTest() {
        Assert.assertTrue(true);
    }

}
