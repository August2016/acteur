/*
 * The MIT License
 *
 * Copyright 2017 Tim Boudreau.
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
package com.mastfrog.marshallers.netty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mastfrog.util.Checks;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.List;
import com.mastfrog.marshallers.Marshaller;

/**
 *
 * @author Tim Boudreau
 */
final class JsonListMarshaller implements Marshaller<List<Object>, ByteBuf> {

    private final ObjectMapper mapper;

    JsonListMarshaller(ObjectMapper mapper) {
        Checks.notNull("mapper", mapper);
        this.mapper = mapper;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Object> read(ByteBuf data, Object[] hints) throws IOException {
        try (final ByteBufInputStream in = new ByteBufInputStream(data)) {
            return mapper.readValue((DataInput) in, List.class);
        }
    }

    @Override
    public void write(List<Object> obj, ByteBuf into, Object[] hints) throws IOException {
        try (final ByteBufOutputStream out = new ByteBufOutputStream(into)) {
            mapper.writeValue((DataOutput) out, obj);
        }
    }

}
