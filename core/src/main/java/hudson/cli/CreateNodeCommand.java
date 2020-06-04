/*
 * The MIT License
 *
 * Copyright 2013 Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hudson.cli;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Node;
import jenkins.model.Jenkins;

import org.kohsuke.args4j.Argument;

/**
 * @author ogondza
 * @since 1.526
 */
@Extension
public class CreateNodeCommand extends CLICommand {

    @Argument(metaVar="NODE", usage="Name of the node")
    public String nodeName;

    @Override
    public String getShortDescription() {

        return Messages.CreateNodeCommand_ShortDescription();
    }

    @Override
    protected int run() throws Exception {

        final Jenkins jenkins = Jenkins.get();
        jenkins.checkPermission(Computer.CREATE);

        final Node newNode = (Node) Jenkins.XSTREAM2.fromXML(stdin);

        if (nodeName != null) {

            // Using deprecated method but it's contract is preserved
            newNode.setNodeName(nodeName);
        }

        if (jenkins.getNode(newNode.getNodeName()) != null) {
            throw new IllegalStateException("Node '" + newNode.getNodeName() + "' already exists");
        }

        jenkins.addNode(newNode);

        return 0;
    }

}
