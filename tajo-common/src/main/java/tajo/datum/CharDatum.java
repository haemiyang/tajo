/*
 * Copyright 2012 Database Lab., Korea Univ.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tajo.datum;

import com.google.gson.annotations.Expose;
import tajo.datum.exception.InvalidOperationException;
import tajo.datum.json.GsonCreator;

public class CharDatum extends Datum {
  private static final int size = 1;
  @Expose char val;

	public CharDatum() {
		super(DatumType.CHAR);
	}

	public CharDatum(byte val) {
		this();
		this.val = (char)val;
	}

  public CharDatum(byte [] bytes) {
    this(bytes[0]);
  }

	public CharDatum(char val) {
	  this();
	  this.val = val;
	}

  @Override
  public char asChar() {
    return val;
  }

  @Override
	public int asInt() {		
		return val;
	}

  @Override
	public long asLong() {
		return val;
	}

  @Override
	public byte asByte() {
		return (byte)val;
	}

  @Override
	public byte[] asByteArray() {
		byte [] bytes = new byte[1];
    bytes[0] = (byte) val;
		return bytes;
	}

  @Override
	public float asFloat() {		
		return val;
	}

  @Override
	public double asDouble() {
		return val;
	}

  @Override
	public String asChars() {
		return String.valueOf(val);
	}

  @Override
	public String toJSON() {
		return GsonCreator.getInstance().toJson(this, Datum.class);
	}

  @Override
  public int size() {
    return size;
  }
  
  @Override
  public int hashCode() {
    return val;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof CharDatum) {
      CharDatum other = (CharDatum) obj;
      return val == other.val;
    }
    
    return false;
  }

  @Override
  public BoolDatum equalsTo(Datum datum) {
    switch (datum.type()) {
    case BYTE:
      return DatumFactory.createBool(this.val == (((CharDatum) datum).val));
    default:
      throw new InvalidOperationException(datum.type());
    }
  }
  
  @Override
  public int compareTo(Datum datum) {
    switch (datum.type()) {
      case BYTE:
      case CHAR:
      if (val < datum.asChar()) {
        return -1;
      } else if (val > datum.asChar()) {
        return 1;
      } else {
        return 0;
      }
    default:
      throw new InvalidOperationException(datum.type());
    }
  }
}
